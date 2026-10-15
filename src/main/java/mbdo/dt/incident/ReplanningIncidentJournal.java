package mbdo.dt.incident;

import mbdo.dt.data.DigitalShadow;
import mbdo.dt.data.DigitalShadow.IncidentRecord;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentRegistryService.RestoredIncident;
import mbdo.dt.replanning.ReplanningPlan;
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
    public static final String REPLANNING = "replanning";
    public static final String RECOVERY = "recovery";

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

    /**
     * Journals the replanning of an anomaly ({@code kind} {@link #REPLANNING}) in its anomaly record, or its recovery
     * ({@link #RECOVERY}) in its recovery record, as it ended: the record is written again, at the same time.
     */
    public void recordReplanning(String anomalyId, String kind, ReplanningPlan plan) {
        registry.findByAnomaly(anomalyId).ifPresentOrElse(incident -> {
            AnomalyContext anomaly = registry.getOriginatingContext(incident.getId()).orElseThrow();
            if (REPLANNING.equals(kind)) {
                digitalShadow.recordAnomaly(anomaly.withReplanningPlan(plan));
            } else if (incident.getResolvedAt() != null) {
                digitalShadow.recordRecovery(anomaly.withRecoveryPlan(plan), incident.getOperatorComment(),
                        incident.getResolvedAt());
            }
        }, () -> logger.warn("No incident for anomaly '{}': its {} isn't journaled", anomalyId, kind));
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
