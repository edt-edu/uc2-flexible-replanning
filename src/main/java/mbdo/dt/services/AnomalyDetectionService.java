package mbdo.dt.services;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.models.SysMLV2Parser;

import java.util.List;
import java.util.Map;

public interface AnomalyDetectionService {

    void activateModels(List<SysMLV2Parser.ParsedSysMLModel> parsedModels);

    AnomalyEvaluationResult evaluateObservation(Map<String, Object> observedValues);

    AnomalyEvaluationResult evaluateObservation(
            DataRequest triggeringRequest,
            Map<String, Object> observedValues
    );

    /** Evaluates the time-based transitions (a state overrunning its time limit), without a new observation. */
    AnomalyEvaluationResult checkTimeTransitions();

    /**
     * The anomalies detected since the last call (one per token), by observations or time-based checks, in the order of
     * detection. Each anomaly is handed out once.
     */
    List<AnomalyEvaluationResult> detectDisruptions();

    void reset();

    /**
     * The operator dealt with an anomaly (its incident was validated): the run of its token ends, the other tokens are
     * still followed. An anomaly of the run waiting for the next token gives a new waiting run.
     *
     * @return false if the anomaly is unknown, or its token's run already ended
     */
    boolean releaseAnomaly(String anomalyId);

    /** The state of the most recent token, or the initial state if no token is on the line. */
    String getCurrentState();

    String getActiveStateMachineId();

    boolean isAnomalyDetected();

    /** The states the most recent token went through. */
    List<String> getTransitionHistory();

    /** Every token on the line (one run of the state machine each), the oldest first. */
    List<TokenRunState> getTokens();

    /**
     * One token followed by the state machine.
     *
     * @param token             its number, in the order the tokens entered the line (from 1)
     * @param state             its current state
     * @param transitionHistory the states it went through, from the initial one
     * @param anomaly           whether an anomaly was detected on it: its run stopped there
     * @param anomalyId         the id of that anomaly, until the operator dealt with it; null without anomaly
     */
    record TokenRunState(int token, String state, List<String> transitionHistory, boolean anomaly, String anomalyId) {
    }

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