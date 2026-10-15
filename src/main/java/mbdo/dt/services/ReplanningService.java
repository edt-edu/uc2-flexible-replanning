package mbdo.dt.services;

import mbdo.dt.domain.DataRequest;
import mbdo.dt.replanning.ReplanningModel;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;

import java.util.Map;

public interface ReplanningService {

    /** The replanning strategies and mission migrations of the SysML models. */
    ReplanningModel getModel();

    /**
     * Looks for the replanning strategy the anomaly triggers, and executes it on the SCADA machines without the
     * operator's approval, as soon as every machine can be migrated. Returns at once: the plan is attached to the
     * anomaly's incident as it progresses.
     *
     * @param latestRuntimeValues the observations when the anomaly was detected, for its first planning (the next ones
     *                            use the latest observations)
     */
    void handleAnomaly(
            DataRequest triggeringRequest,
            AnomalyEvaluationResult anomalyResult,
            Map<String, Object> latestRuntimeValues
    );

    /**
     * Plans a strategy on the machines' current states, without executing it (dry run).
     *
     * @throws IllegalArgumentException if no such strategy exists
     */
    ReplanningPlan planStrategy(String strategyName);
}
