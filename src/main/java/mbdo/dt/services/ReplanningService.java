package mbdo.dt.services;

import mbdo.dt.models.SysMLV2Parser.ParsedSysMLModel;
import mbdo.dt.services.AnomalyDetectionService.AnomalyEvaluationResult;

import java.util.List;
import java.util.Map;

public interface ReplanningService {

    /**
     * Uses the replanning strategies, mission migrations and machine stops of the parsed SysML models (replacing the
     * previous ones).
     */
    void activateModels(List<ParsedSysMLModel> parsedModels);

    /**
     * Looks for the replanning strategy the anomaly triggers, and executes it on the SCADA machines without the
     * operator's approval, as soon as every machine can be migrated. Returns at once: the plan is attached to the
     * anomaly's incident as it progresses.
     *
     * @param latestRuntimeValues the observations when the anomaly was detected, for its first planning (the next ones
     *                            use the latest observations)
     */
    void handleDisruption(
            AnomalyEvaluationResult anomalyResult,
            Map<String, Object> latestRuntimeValues
    );

    /**
     * The operator repaired the failure of the anomaly {@code anomalyId}: executes the recovery strategy of the
     * strategy it triggered, if any (back to the nominal missions), without the operator's approval. Returns at once.
     */
    void recover(String anomalyId);
}
