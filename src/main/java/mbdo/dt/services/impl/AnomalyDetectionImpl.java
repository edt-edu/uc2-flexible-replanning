package mbdo.dt.services.impl;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.models.RuntimeInterpreter;
import mbdo.dt.models.StateMachineSelector;
import mbdo.dt.models.SysMLBehavioralModel;
import mbdo.dt.models.SysMLBehavioralModel.StateMachine;
import mbdo.dt.models.SysMLBehavioralModel.Transition;
import mbdo.dt.models.SysMLStructuralModel;
import mbdo.dt.models.SysMLStructuralModel.ConstraintUsage;
import mbdo.dt.models.SysMLV2Parser;
import mbdo.dt.services.AnomalyDetection;
import mbdo.dt.services.AnomalyReactionService;
import mbdo.dt.utils.RuntimeInterpreterUtils;
import mbdo.dt.utils.StateMachineInferenceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

@Service
public class AnomalyDetectionImpl implements AnomalyDetection {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyDetectionImpl.class);

    private final LongSupplier clockMillis;
    private final List<String> transitionHistory = new ArrayList<>();

    private SysMLStructuralModel structuralModel;

    private List<SysMLStructuralModel> allStructuralModels = List.of();

    private SysMLBehavioralModel behavioralModel;
    private StateMachine activeStateMachine;

    private String activeStateMachineId;
    private String currentState;
    private boolean anomalyDetected;
    private long stateEnteredAtMillis;
    private final RuntimeInterpreter runtimeInterpreter;
    private final StateMachineSelector stateMachineSelector;
    private final AnomalyReactionService anomalyReactionService;
    private final String primaryStateMachineName;

    @Autowired
    public AnomalyDetectionImpl(
            RuntimeInterpreter runtimeInterpreter,
            StateMachineSelector stateMachineSelector,
            AnomalyReactionService anomalyReactionService,
            @Value("${anomaly-detection.primary-state-machine:TokenLocation}") String primaryStateMachineName
    ) {
        this(System::currentTimeMillis, runtimeInterpreter, stateMachineSelector, anomalyReactionService, primaryStateMachineName);
    }

    AnomalyDetectionImpl(
            LongSupplier clockMillis,
            RuntimeInterpreter runtimeInterpreter,
            StateMachineSelector stateMachineSelector,
            AnomalyReactionService anomalyReactionService,
            String primaryStateMachineName
    ) {
        this.clockMillis = clockMillis;
        this.runtimeInterpreter = runtimeInterpreter;
        this.stateMachineSelector = stateMachineSelector;
        this.anomalyReactionService = anomalyReactionService;
        this.primaryStateMachineName = primaryStateMachineName;
    }

    @Override
    public synchronized void activateModels(List<SysMLV2Parser.ParsedSysMLModel> parsedModels) {
        if (parsedModels == null || parsedModels.isEmpty()) {
            throw new IllegalStateException("No parsed sysml model was found");
        }

        StateMachineSelector.ModelStateMachine selected =
                stateMachineSelector.select(parsedModels, primaryStateMachineName);

        structuralModel = selected.structuralModel();
        behavioralModel = selected.behavioralModel();
        activeStateMachine = selected.stateMachine();
        activeStateMachineId = activeStateMachine.name();

        allStructuralModels = parsedModels.stream()
                .filter(Objects::nonNull)
                .map(SysMLV2Parser.ParsedSysMLModel::structuralModel)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        reset();

        logger.info("Anomaly detection activated for state machine: {}",
                activeStateMachineId);
    }

    @Override
    public synchronized void reset() {
        anomalyDetected = false;
        transitionHistory.clear();

        if (activeStateMachine == null) {
            currentState = null;
            stateEnteredAtMillis = 0L;
            return;
        }

        String initialState = activeStateMachine.initialState();

        if (initialState == null || initialState.isBlank()) {
            throw new IllegalStateException("State machine '" + activeStateMachine.name() + "' has no initial state. Expected 'entry; then <State>;' in the SysML model."
            );
        }

        currentState = initialState;
        stateEnteredAtMillis = clockMillis.getAsLong();

        addToTransitionHistory(currentState);
    }

    @Override
    public synchronized AnomalyEvaluationResult evaluateObservation(Map<String, Object> observedValues) {
        return evaluateObservation(null, observedValues);
    }

    @Override
    public synchronized AnomalyEvaluationResult evaluateObservation(DataRequest triggeringRequest, Map<String, Object> observedValues) {
        Map<String, Object> safeObservedValues =
                observedValues == null ? Collections.emptyMap() : observedValues;

        if (activeStateMachine == null) {
            return nonAnomalyResult(
                    EvaluationStatus.MODEL_NOT_LOADED,
                    null,
                    null,
                    null,
                    "No anomaly detection state machine has been loaded."
            );
        }

        if (currentState == null) {
            return nonAnomalyResult(
                    EvaluationStatus.MODEL_NOT_LOADED,
                    null,
                    null,
                    null,
                    "The anomaly detection state machine has no current state."
            );
        }

        if (anomalyDetected) {
            return nonAnomalyResult(
                    EvaluationStatus.ANOMALY_DETECTED,
                    currentState,
                    currentState,
                    null,
                    "An anomaly has already been detected. Reset is required before continuing."
            );
        }

        String stateBeforeEvaluation = currentState;

        AnomalyEvaluationResult result = evaluateCurrentState(safeObservedValues);

        if (logger.isDebugEnabled()) {
            logger.debug("observation {}={}, state {} -> {}, status={}, {}",
                    triggeringRequest == null ? null : triggeringRequest.attributeName(),
                    triggeringRequest == null ? null : triggeringRequest.value(),
                    stateBeforeEvaluation,
                    result.currentState(),
                    result.status(),
                    result.message());
        }

        anomalyReactionService.handleDetectedAnomaly(triggeringRequest, result, safeObservedValues);

        return result;
    }

    @Override
    public synchronized AnomalyEvaluationResult checkTimeTransitions() {
        return evaluateObservation(Collections.emptyMap());
    }

    @Override
    public synchronized String getCurrentState() {
        return currentState;
    }

    @Override
    public synchronized String getActiveStateMachineId() {
        return activeStateMachineId;
    }

    @Override
    public synchronized boolean isAnomalyDetected() {
        return anomalyDetected;
    }

    @Override
    public synchronized List<String> getTransitionHistory() {
        return List.copyOf(transitionHistory);
    }

    private static final int MAX_CASCADED_TRANSITIONS_PER_OBSERVATION = 25;

    private AnomalyEvaluationResult evaluateCurrentState(Map<String, Object> observedValues) {
        long now = resolveObservationTimeMillis(observedValues);

        AnomalyEvaluationResult settledResult = null;

        for (int step = 0; step < MAX_CASCADED_TRANSITIONS_PER_OBSERVATION; step++) {
            AnomalyEvaluationResult durationConstraintResult =
                    evaluateStateDurationLimit(now);

            if (durationConstraintResult != null) {
                return durationConstraintResult;
            }

            AnomalyEvaluationResult timeoutTransitionResult =
                    evaluateTimeoutTransitions(now);

            if (timeoutTransitionResult != null) {
                return timeoutTransitionResult;
            }

            AnomalyEvaluationResult guardTransitionResult =
                    evaluateGuardTransitions(observedValues, now);

            if (guardTransitionResult == null) {
                return settledResult != null ? settledResult : waitingResult(now);
            }

            settledResult = guardTransitionResult;

            if (guardTransitionResult.status() != EvaluationStatus.TRANSITION_FIRED) {
                return guardTransitionResult;
            }
        }

        anomalyDetected = true;

        return anomalyResult(
                EvaluationStatus.AMBIGUOUS_TRANSITION,
                currentState,
                currentState,
                null,
                "More than " + MAX_CASCADED_TRANSITIONS_PER_OBSERVATION
                        + " transitions cascaded from a single observation without settling"
                        + " - this looks like a cycle in the state machine rather than a real"
                        + " sequence of transitions."
        );
    }

    private AnomalyEvaluationResult evaluateTimeoutTransitions(long now) {
        List<Transition> elapsedTimeTransitions =
                findElapsedRelativeTimeTransitions(now);

        if (elapsedTimeTransitions.size() == 1) {
            return fireTransition(elapsedTimeTransitions.get(0), now);
        }

        if (elapsedTimeTransitions.size() > 1) {
            anomalyDetected = true;

            return anomalyResult(
                    EvaluationStatus.AMBIGUOUS_TRANSITION,
                    currentState,
                    currentState,
                    null,
                    "Several timeout transitions are enabled from state '" + currentState + "'."
            );
        }

        return null;
    }

    private AnomalyEvaluationResult evaluateGuardTransitions(
            Map<String, Object> observedValues,
            long now
    ) {
        List<Transition> enabledGuardTransitions =
                findEnabledGuardTransitions(observedValues);

        if (enabledGuardTransitions.size() == 1) {
            return fireTransition(enabledGuardTransitions.get(0), now);
        }

        if (enabledGuardTransitions.size() > 1) {
            anomalyDetected = true;

            return anomalyResult(
                    EvaluationStatus.AMBIGUOUS_TRANSITION,
                    currentState,
                    currentState,
                    null,
                    "Several guard-based transitions are enabled from state '" + currentState + "'."
            );
        }

        return null;
    }

    private AnomalyEvaluationResult waitingResult(long now) {
        Long remainingMillis = millisUntilNextRelativeTimeTransition(now);
        String message = "No complete guard is satisfied yet from state '" + currentState + "'.";

        if (remainingMillis != null) {
            message += " Timeout may occur in " + remainingMillis + " ms.";
        }

        return nonAnomalyResult(
                EvaluationStatus.WAITING_FOR_MATCH,
                currentState,
                currentState,
                null,
                message
        );
    }

    private List<Transition> relativeTimeTransitionsFromCurrentState() {
        List<Transition> transitions = new ArrayList<>();

        for (Transition transition : activeStateMachine.transitions()) {
            if (!transition.sourceState().equals(currentState)) {
                continue;
            }

            if (RuntimeInterpreterUtils.isRelativeTimeTransition(transition)) {
                transitions.add(transition);
            }
        }

        return transitions;
    }

    private List<Transition> findElapsedRelativeTimeTransitions(long now) {
        List<Transition> elapsedTransitions = new ArrayList<>();

        for (Transition transition : relativeTimeTransitionsFromCurrentState()) {
            if (hasRelativeTimeElapsed(transition, now)) {
                elapsedTransitions.add(transition);
            }
        }

        return elapsedTransitions;
    }

    private List<Transition> findEnabledGuardTransitions(Map<String, ?> observedValues) {
        return runtimeInterpreter.findEnabledGuardTransitions(
                activeStateMachine, currentState, structuralModel, allStructuralModels, observedValues);
    }

    private AnomalyEvaluationResult fireTransition(Transition firedTransition, long now) {
        String previousState = currentState;
        String targetState = firedTransition.targetState();

        if (RuntimeInterpreterUtils.isRelativeTimeTransition(firedTransition)) {
            anomalyDetected = true;

            return anomalyResult(
                    EvaluationStatus.ANOMALY_DETECTED,
                    previousState,
                    previousState,
                    firedTransition.name(),
                    "Timeout deviation. State '" + previousState
                            + "' remained active until relative-time transition '"
                            + firedTransition.name() + "' fired."
            );
        }

        currentState = targetState;
        stateEnteredAtMillis = now;

        addToTransitionHistory(currentState);

        logger.info("Transition '{}' fired: {} -> {}",
                firedTransition.name(), previousState, currentState);

        return nonAnomalyResult(
                EvaluationStatus.TRANSITION_FIRED,
                previousState,
                currentState,
                firedTransition.name(),
                "Transition '" + firedTransition.name() + "' fired: "
                        + previousState + " -> " + currentState
        );
    }

    private boolean hasRelativeTimeElapsed(Transition transition, long now) {
        Long delayMillis = transition.delayMillis();

        if (delayMillis == null) {
            return false;
        }

        long elapsedMillis = now - stateEnteredAtMillis;
        return elapsedMillis >= delayMillis;
    }

    private Long millisUntilNextRelativeTimeTransition(long now) {
        Long shortestRemainingMillis = null;

        for (Transition transition : relativeTimeTransitionsFromCurrentState()) {
            Long delayMillis = transition.delayMillis();

            if (delayMillis == null) {
                continue;
            }

            long elapsedMillis = now - stateEnteredAtMillis;
            long remainingMillis = Math.max(0L, delayMillis - elapsedMillis);

            if (shortestRemainingMillis == null || remainingMillis < shortestRemainingMillis) {
                shortestRemainingMillis = remainingMillis;
            }
        }

        return shortestRemainingMillis;
    }

    private AnomalyEvaluationResult evaluateStateDurationLimit(long now) {
        if (structuralModel == null) {
            return null;
        }

        long elapsedMillis = now - stateEnteredAtMillis;

        for (ConstraintUsage constraintUsage
                : structuralModel.findConstraintUsagesByType("StateDurationLimit")) {

            String constrainedStateName =
                    constraintUsage.inputValues().get("constrainedStateName");

            if (!RuntimeInterpreterUtils.valuesMatch(constrainedStateName, currentState)) {
                continue;
            }

            String maxDurationExpression =
                    constraintUsage.inputValues().get("maxDuration");

            Long maxDurationMillis =
                    RuntimeInterpreterUtils.parseDurationMillisOrNull(maxDurationExpression);

            if (maxDurationMillis == null) {
                continue;
            }

            if (elapsedMillis >= maxDurationMillis) {
                anomalyDetected = true;

                return anomalyResult(
                        EvaluationStatus.ANOMALY_DETECTED,
                        currentState,
                        currentState,
                        constraintUsage.name(),
                        "State duration constraint '" + constraintUsage.name()
                                + "' violated. State '" + currentState
                                + "' has been active for " + elapsedMillis
                                + " ms, but the maximum allowed duration is "
                                + maxDurationMillis + " ms."
                );
            }
        }

        return null;
    }

    private long resolveObservationTimeMillis(Map<String, ?> observedValues) {
        Object timestamp = observedValues.get("timestamp");

        if (timestamp == null) {
            return clockMillis.getAsLong();
        }

        if (timestamp instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(timestamp).trim());
        } catch (NumberFormatException e) {
            logger.warn("Could not parse timestamp value '{}' as a long. Using wall clock.", timestamp);
            return clockMillis.getAsLong();
        }
    }

    private void addToTransitionHistory(String state) {
        if (state == null || state.isBlank()) {
            return;
        }

        if (transitionHistory.isEmpty()
                || !transitionHistory.get(transitionHistory.size() - 1).equals(state)) {
            transitionHistory.add(state);
        }
    }

    private String activeStateMachineName() {
        return activeStateMachine == null ? null : activeStateMachine.name();
    }

    private AnomalyEvaluationResult buildResult(
            EvaluationStatus status,
            String previousState,
            String currentState,
            String transitionName,
            String message,
            AnomalyContext anomalyContext
    ) {
        return new AnomalyEvaluationResult(
                status,
                activeStateMachineId,
                activeStateMachineName(),
                previousState,
                currentState,
                transitionName,
                message,
                anomalyContext
        );
    }

    private AnomalyEvaluationResult anomalyResult(
            EvaluationStatus status,
            String previousState,
            String currentState,
            String transitionName,
            String message
    ) {
        String affectedComponent = StateMachineInferenceUtils.inferAffectedComponent(activeStateMachine, previousState);

        if (affectedComponent == null) {
            logger.warn(
                    "Could not infer a unique affectedComponent from source state '{}' in state machine '{}'. Replanning will receive a null component.",
                    previousState,
                    activeStateMachineId
            );
        }

        AnomalyContext anomalyContext = new AnomalyContext(
                UUID.randomUUID().toString(),
                Instant.ofEpochMilli(clockMillis.getAsLong()),
                activeStateMachineName(),
                previousState,
                affectedComponent,
                previousState,
                currentState,
                message
        );

        return buildResult(status, previousState, currentState, transitionName, message, anomalyContext);
    }

    private AnomalyEvaluationResult nonAnomalyResult(
            EvaluationStatus status,
            String previousState,
            String currentState,
            String transitionName,
            String message
    ) {
        return buildResult(status, previousState, currentState, transitionName, message, null);
    }
}