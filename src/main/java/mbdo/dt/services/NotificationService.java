package mbdo.dt.services;

import mbdo.dt.domain.AnomalyContext;

public interface NotificationService {

    /**
     * Raises a replanning incident notification for a detected anomaly, pushed live to
     * the factory-supervision UI (see {@link mbdo.dt.incident.ReplanningIncidentController}).
     */
    void notifyAnomaly(AnomalyContext context);
}
