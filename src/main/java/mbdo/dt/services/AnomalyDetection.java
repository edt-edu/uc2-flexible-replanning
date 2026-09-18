package mbdo.dt.services;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.models.SysMLV2Parser;

import java.util.List;
import java.util.Map;

public interface AnomalyDetection {

    void activateModels(List<SysMLV2Parser.ParsedSysMLModel> parsedModels);

    AnomalyEvaluationResult evaluateObservation(Map<String, Object> observedValues);

    AnomalyEvaluationResult evaluateObservation(
            DataRequest triggeringRequest,
            Map<String, Object> observedValues
    );

    AnomalyEvaluationResult checkTimeTransitions();

    void reset();

    String getCurrentState();

    String getActiveStateMachineId();

    boolean isAnomalyDetected();

    List<String> getTransitionHistory();

    enum EvaluationStatus {
        MODEL_NOT_LOADED,
        TRANSITION_FIRED,
        WAITING_FOR_MATCH,
        AMBIGUOUS_TRANSITION,
        ANOMALY_DETECTED
    }

    record AnomalyEvaluationResult(
            EvaluationStatus status,
            String activeStateMachineId,
            String activeStateMachineName,
            String previousState,
            String currentState,
            String transitionName,
            String message,
            AnomalyContext anomalyContext
    ) {
    }
}