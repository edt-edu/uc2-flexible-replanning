package mbdo.dt.services;

import mbdo.dt.domain.DataRequest;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;

import java.util.Map;

public interface ReplanningService {

    void handleAnomaly(
            DataRequest triggeringRequest,
            AnomalyEvaluationResult anomalyResult,
            Map<String, Object> latestRuntimeValues
    );
}