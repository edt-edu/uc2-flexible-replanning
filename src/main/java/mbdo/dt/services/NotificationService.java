package mbdo.dt.services;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentResponse;

import java.util.Optional;

/**
 * The lifecycle of the replanning incident of a disruption (an anomaly), journaled (InfluxDB) and pushed live to the
 * factory-supervision UI (see {@link mbdo.dt.incident.ReplanningIncidentController}). The context carries the progress:
 * its {@link AnomalyContext#replanningPlan()} while the incident is open, its {@link AnomalyContext#recoveryPlan()}
 * once it is closed. A plan is journaled when it ended (executed, failed, or dropped as its incident was closed).
 */
public interface NotificationService {

    /**
     * Opens the incident of the disruption, or shows its replanning plan in its open incident.
     *
     * @return false if the incident is no longer open: the replanning is dropped
     */
    boolean notifyDisruption(AnomalyContext context);

    /**
     * The operator corrected the disruption: closes its open incident. Called again with a recovery plan (back to the
     * nominal missions), shows that plan in the closed incident.
     *
     * @return the closed incident, or empty if the incident of {@code context} is not open (without a recovery plan)
     * or doesn't exist
     */
    Optional<ReplanningIncidentResponse> closeDisruption(AnomalyContext context, String comment);
}
