package mbdo.dt.incident;

import mbdo.dt.data.DigitalShadow;
import mbdo.dt.data.DigitalShadow.IncidentRecord;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentRegistryService.RestoredIncident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The journal of the incidents, in InfluxDB (see {@link DigitalShadow}): the anomaly records, with the replanning they
 * triggered, and the recovery records (the operator's correction), with the recovery back to the nominal missions.
 * Read back at startup, so that the history of the incidents survives a restart of the digital twin.
 */
@Component
public class ReplanningIncidentJournal {

    private static final Logger logger = LoggerFactory.getLogger(ReplanningIncidentJournal.class);

    private final DigitalShadow digitalShadow;
    private final ReplanningIncidentRegistryService registry;
    private final Duration lookback;

    public ReplanningIncidentJournal(
            DigitalShadow digitalShadow,
            ReplanningIncidentRegistryService registry,
            @Value("${incidents.journal-lookback:P7D}") Duration lookback
    ) {
        this.digitalShadow = digitalShadow;
        this.registry = registry;
        this.lookback = lookback;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        List<IncidentRecord> records;
        try {
            records = digitalShadow.loadIncidents(lookback);
        } catch (RuntimeException e) {
            logger.warn("Could not read the incident journal from InfluxDB, no past incident: {}", e.getMessage());
            return;
        }
        registry.restoreHistory(records.stream().map(ReplanningIncidentJournal::restored).toList());
        logger.info("Restored {} past incidents from the journal (last {}).", records.size(), lookback);
    }

    /** Journals the anomaly, with its replanning plan if any: written again, at the same time, InfluxDB merges it. */
    public void recordAnomaly(AnomalyContext anomaly) {
        digitalShadow.recordAnomaly(anomaly);
    }

    /**
     * Journals the operator's correction of the anomaly, at the time its incident was resolved, with its recovery plan
     * if any.
     */
    public void recordRecovery(AnomalyContext anomaly, String comment, Instant resolvedAt) {
        digitalShadow.recordRecovery(anomaly, comment, resolvedAt);
    }

    /** An anomaly never repaired (the digital twin stopped meanwhile) ends with its detection. */
    private static RestoredIncident restored(IncidentRecord record) {
        if (record.recoveredAt() != null) {
            return new RestoredIncident(record.anomaly(), record.recoveredAt(), record.comment());
        }
        Instant detectedAt = record.anomaly().detectionTimestamp();
        return new RestoredIncident(record.anomaly(), detectedAt,
                "Not repaired: the digital twin stopped before the operator's correction");
    }
}
