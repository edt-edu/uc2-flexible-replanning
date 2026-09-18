package mbdo.dt.services;

import mbdo.dt.domain.DataRequest;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;

import java.util.Map;

/**
 * Reacts to a detected anomaly once evaluation has settled: persists it and hands it to
 * replanning. Independent of the state-machine evaluation itself - it consumes a finished
 * {@link AnomalyEvaluationResult}, it doesn't participate in producing one.
 */
public interface AnomalyReactionService {

    void handleDetectedAnomaly(
            DataRequest triggeringRequest,
            AnomalyEvaluationResult result,
            Map<String, Object> latestRuntimeValues
    );
}
