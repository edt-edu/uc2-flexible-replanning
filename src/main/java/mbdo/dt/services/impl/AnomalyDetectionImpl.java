package mbdo.dt.services.impl;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.models.RuntimeInterpreter;
import mbdo.dt.models.RuntimeInterpreter.GuardClauseEvaluation;
import mbdo.dt.models.RuntimeInterpreter.GuardTransitionEvaluation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Follows every token on the line with its own run of the monitored state machine (TokenLocation).
 *
 * <p>One run always waits in the initial state for the next token. When it leaves the initial state (a token at the
 * SortingLine input), it becomes that token's run, and a new run waits for the next token. That new run is only armed
 * once its entry guard no longer holds (the input is free again), so that the same token isn't counted twice. A token's
 * run that comes back to the initial state (the token left the line) ends.
 *
 * <p>Every observation is evaluated on each run, the oldest token first, so that a token leaves a location before the
 * next one enters it; a run doesn't enter a state another token is in. An anomaly stops only the run of its token: the
 * other tokens are still followed, and new tokens still enter the line.
 */
@Service
public class AnomalyDetectionImpl implements AnomalyDetection {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyDetectionImpl.class);

    private final LongSupplier clockMillis;

    private SysMLStructuralModel structuralModel;

    private List<SysMLStructuralModel> allStructuralModels = List.of();

    private SysMLBehavioralModel behavioralModel;
    private StateMachine activeStateMachine;

    private String activeStateMachineId;
    private final RuntimeInterpreter runtimeInterpreter;
    private final StateMachineSelector stateMachineSelector;
    private final AnomalyReactionService anomalyReactionService;
    private final String primaryStateMachineName;

    /** The runs of the state machine, the oldest token first; the run waiting for the next token is the last one. */
    private final List<TokenRun> runs = new ArrayList<>();
    private int nextTokenNumber = 1;

    /** The run of each anomaly, until the operator dealt with it ({@link #releaseAnomaly}). */
    private final Map<String, TokenRun> anomalyRuns = new HashMap<>();

    /** The run being evaluated (every evaluation holds the lock of this service). */
    private TokenRun run;

    /** One token followed by the state machine (number 0: the run waiting for the next token). */
    private static final class TokenRun {
        int token;
        String currentState;
        long stateEnteredAtMillis;
        boolean anomalyDetected;
        /** For the waiting run: whether its entry guard was seen false since it was created. */
        boolean armed;
        final List<String> transitionHistory = new ArrayList<>();

        boolean waiting() {
            return token == 0;
        }
    }

    @Autowired
    public AnomalyDetectionImpl(
            RuntimeInterpreter runtimeInterpreter,
            StateMachineSelector stateMachineSelector,
            AnomalyReactionService anomalyReactionService,
            @Value("${anomaly-detection.primary-state-machine:TokenLocation}") String primaryStateMachineName) {
        this(System::currentTimeMillis, runtimeInterpreter, stateMachineSelector, anomalyReactionService,
                primaryStateMachineName);
    }

    AnomalyDetectionImpl(
            LongSupplier clockMillis,
            RuntimeInterpreter runtimeInterpreter,
            StateMachineSelector stateMachineSelector,
            AnomalyReactionService anomalyReactionService,
            String primaryStateMachineName) {
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

        StateMachineSelector.ModelStateMachine selected = stateMachineSelector.select(parsedModels,
                primaryStateMachineName);

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
        runs.clear();
        anomalyRuns.clear();
        nextTokenNumber = 1;

        if (activeStateMachine == null) {
            return;
        }

        String initialState = activeStateMachine.initialState();

        if (initialState == null || initialState.isBlank()) {
            throw new IllegalStateException("State machine '" + activeStateMachine.name()
                    + "' has no initial state. Expected 'entry; then <State>;' in the SysML model.");
        }

        // armed at once: a token already at the input when the monitoring starts is the first one
        runs.add(newWaitingRun(true));
    }

    @Override
    public synchronized boolean releaseAnomaly(String anomalyId) {
        TokenRun anomalyRun = anomalyRuns.remove(anomalyId);
        if (anomalyRun == null || !runs.remove(anomalyRun)) {
            return false;
        }
        if (anomalyRun.waiting()) {
            runs.add(newWaitingRun(false));
            logger.info("Anomaly '{}' dealt with: a new run waits for the next token", anomalyId);
        } else {
            logger.info("Anomaly '{}' dealt with: token #{} is no longer followed", anomalyId, anomalyRun.token);
        }
        return true;
    }

    private TokenRun newWaitingRun(boolean armed) {
        TokenRun waiting = new TokenRun();
        waiting.currentState = activeStateMachine.initialState();
        waiting.stateEnteredAtMillis = clockMillis.getAsLong();
        waiting.armed = armed;
        waiting.transitionHistory.add(waiting.currentState);
        return waiting;
    }

    @Override
    public synchronized AnomalyEvaluationResult evaluateObservation(Map<String, Object> observedValues) {
        return evaluateObservation(null, observedValues);
    }

    @Override
    public synchronized AnomalyEvaluationResult evaluateObservation(DataRequest triggeringRequest,
            Map<String, Object> observedValues) {
        Map<String, Object> safeObservedValues = observedValues == null ? Collections.emptyMap() : observedValues;

        if (activeStateMachine == null || runs.isEmpty()) {
            return nonAnomalyResult(
                    EvaluationStatus.MODEL_NOT_LOADED,
                    null,
                    null,
                    null,
                    "No anomaly detection state machine has been loaded.");
        }

        List<AnomalyEvaluationResult> results = new ArrayList<>();

        for (TokenRun tokenRun : List.copyOf(runs)) {
            if (tokenRun.anomalyDetected) {
                continue;
            }
            if (tokenRun.waiting() && !tokenRun.armed) {
                tokenRun.armed = !initialTransitionEnabled(safeObservedValues);
                continue;
            }

            run = tokenRun;
            String stateBeforeEvaluation = tokenRun.currentState;
            AnomalyEvaluationResult result = evaluateCurrentState(safeObservedValues);
            logGuards(triggeringRequest, stateBeforeEvaluation, result, safeObservedValues);
            afterEvaluation(tokenRun);
            results.add(result);
        }
        run = null;

        return react(triggeringRequest, results, safeObservedValues);
    }

    private void logGuards(DataRequest triggeringRequest, String stateBeforeEvaluation,
            AnomalyEvaluationResult result, Map<String, Object> observedValues) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        List<GuardTransitionEvaluation> partlyTrueGuards = runtimeInterpreter.describeGuardTransitions(
                        activeStateMachine, stateBeforeEvaluation, structuralModel, allStructuralModels, observedValues)
                .stream()
                .filter(GuardTransitionEvaluation::anyClauseTrue)
                .toList();
        if (!partlyTrueGuards.isEmpty()) {
            logger.debug("observation {}={}, {} {} -> {}, status={}, {}{}",
                    triggeringRequest == null ? null : triggeringRequest.attributeName(),
                    triggeringRequest == null ? null : triggeringRequest.value(),
                    describe(run),
                    stateBeforeEvaluation,
                    result.currentState(),
                    result.status(),
                    result.message(),
                    formatGuards(partlyTrueGuards));
        }
    }

    /**
     * After a run was evaluated: the waiting run that left the initial state becomes a token and a new run waits for
     * the next token; a token's run back in the initial state ends.
     */
    private void afterEvaluation(TokenRun tokenRun) {
        boolean inInitialState = activeStateMachine.initialState().equals(tokenRun.currentState);
        if (tokenRun.waiting() && !inInitialState) {
            tokenRun.token = nextTokenNumber++;
            runs.add(newWaitingRun(false));
            logger.info("Token #{} entered the line ({})", tokenRun.token, tokenRun.currentState);
        } else if (!tokenRun.waiting() && inInitialState && !tokenRun.anomalyDetected) {
            runs.remove(tokenRun);
            logger.info("Token #{} left the line", tokenRun.token);
        }
    }

    /** Whether a transition leaving the initial state is enabled: the next token is at the entry. */
    private boolean initialTransitionEnabled(Map<String, ?> observedValues) {
        return !runtimeInterpreter.findEnabledGuardTransitions(activeStateMachine, activeStateMachine.initialState(),
                structuralModel, allStructuralModels, observedValues).isEmpty();
    }

    /**
     * Hands every anomaly to the reaction (one per token), and returns the first anomaly, else the last transition
     * fired, else the waiting result of the most recent token.
     */
    private AnomalyEvaluationResult react(DataRequest triggeringRequest, List<AnomalyEvaluationResult> results,
            Map<String, Object> observedValues) {
        List<AnomalyEvaluationResult> anomalies = results.stream().filter(r -> r.anomalyContext() != null).toList();
        AnomalyEvaluationResult summary;
        if (!anomalies.isEmpty()) {
            summary = anomalies.get(0);
        } else {
            summary = results.stream()
                    .filter(r -> r.status() == EvaluationStatus.TRANSITION_FIRED)
                    .reduce((first, second) -> second)
                    .orElseGet(() -> results.isEmpty() ? idleResult() : mostRecent(results));
        }

        if (anomalies.isEmpty()) {
            anomalyReactionService.handleDetectedAnomaly(triggeringRequest, summary, observedValues);
        } else {
            anomalies.forEach(anomaly ->
                    anomalyReactionService.handleDetectedAnomaly(triggeringRequest, anomaly, observedValues));
        }
        return summary;
    }

    /** The result of the most recent token: the last one before the waiting run's, if any. */
    private static AnomalyEvaluationResult mostRecent(List<AnomalyEvaluationResult> results) {
        return results.size() > 1 ? results.get(results.size() - 2) : results.get(0);
    }

    private AnomalyEvaluationResult idleResult() {
        String state = getCurrentState();
        return nonAnomalyResult(EvaluationStatus.WAITING_FOR_MATCH, state, state, null,
                "Waiting for the next token (the entry must be free first).");
    }

    /** Each guard transition from {@code state}, with the value and result of each clause (for diagnostics). */
    synchronized List<GuardTransitionEvaluation> describeGuards(String state, Map<String, ?> observedValues) {
        if (activeStateMachine == null) {
            return List.of();
        }
        return runtimeInterpreter.describeGuardTransitions(
                activeStateMachine, state, structuralModel, allStructuralModels, observedValues);
    }

    private static final String BOLD_GREEN = "\u001B[1;32m";
    private static final String BOLD_RED = "\u001B[1;31m";
    private static final String RESET = "\u001B[0m";

    /**
     * Formats each transition, then each of its clauses as "expected: [clause], found: [values]", in bold green if
     * true, bold red otherwise.
     */
    static String formatGuards(List<GuardTransitionEvaluation> guards) {
        StringBuilder text = new StringBuilder();
        for (GuardTransitionEvaluation guard : guards) {
            text.append(System.lineSeparator()).append("  ").append(guard.transitionName()).append(':');
            for (GuardClauseEvaluation clause : guard.clauses()) {
                boolean satisfied = clause.result() == GuardClauseEvaluation.Result.TRUE;
                text.append(System.lineSeparator()).append("    ")
                        .append(satisfied ? BOLD_GREEN : BOLD_RED)
                        .append("expected: [").append(clause.expected())
                        .append("], found: ")
                        .append(clause.found().stream().toList())
                        .append(RESET);
            }
        }
        return text.toString();
    }

    @Override
    public synchronized AnomalyEvaluationResult checkTimeTransitions() {
        // Only the time-based rules can change without a new observation: guards are evaluated when values arrive.
        if (activeStateMachine == null || runs.isEmpty()) {
            return nonAnomalyResult(EvaluationStatus.MODEL_NOT_LOADED, null, null, null,
                    "No anomaly detection state machine has been loaded.");
        }

        long now = clockMillis.getAsLong();
        List<AnomalyEvaluationResult> results = new ArrayList<>();
        for (TokenRun tokenRun : List.copyOf(runs)) {
            if (tokenRun.anomalyDetected || (tokenRun.waiting() && !tokenRun.armed)) {
                continue;
            }
            run = tokenRun;
            AnomalyEvaluationResult result = evaluateStateDurationLimit(now);
            if (result == null) {
                result = evaluateTimeoutTransitions(now);
            }
            if (result == null) {
                result = waitingResult(now);
            }
            afterEvaluation(tokenRun);
            results.add(result);
        }
        run = null;

        return react(null, results, Collections.emptyMap());
    }

    @Override
    public synchronized String getCurrentState() {
        TokenRun latest = latestToken();
        return latest == null ? null : latest.currentState;
    }

    @Override
    public synchronized String getActiveStateMachineId() {
        return activeStateMachineId;
    }

    @Override
    public synchronized boolean isAnomalyDetected() {
        return runs.stream().anyMatch(r -> r.anomalyDetected);
    }

    @Override
    public synchronized List<String> getTransitionHistory() {
        TokenRun latest = latestToken();
        return latest == null ? List.of() : List.copyOf(latest.transitionHistory);
    }

    @Override
    public synchronized List<TokenRunState> getTokens() {
        return runs.stream()
                .filter(r -> !r.waiting())
                .map(r -> new TokenRunState(r.token, r.currentState, List.copyOf(r.transitionHistory),
                        r.anomalyDetected, anomalyIdOf(r)))
                .toList();
    }

    private String anomalyIdOf(TokenRun tokenRun) {
        return anomalyRuns.entrySet().stream()
                .filter(entry -> entry.getValue() == tokenRun)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** The most recent token's run, or the waiting run if no token is on the line. */
    private TokenRun latestToken() {
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (!runs.get(i).waiting()) {
                return runs.get(i);
            }
        }
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    private static final int MAX_CASCADED_TRANSITIONS_PER_OBSERVATION = 25;

    private AnomalyEvaluationResult evaluateCurrentState(Map<String, Object> observedValues) {
        long now = resolveObservationTimeMillis(observedValues);

        AnomalyEvaluationResult settledResult = null;

        for (int step = 0; step < MAX_CASCADED_TRANSITIONS_PER_OBSERVATION; step++) {
            AnomalyEvaluationResult durationConstraintResult = evaluateStateDurationLimit(now);

            if (durationConstraintResult != null) {
                return durationConstraintResult;
            }

            AnomalyEvaluationResult timeoutTransitionResult = evaluateTimeoutTransitions(now);

            if (timeoutTransitionResult != null) {
                return timeoutTransitionResult;
            }

            AnomalyEvaluationResult guardTransitionResult = evaluateGuardTransitions(observedValues, now);

            if (guardTransitionResult == null) {
                return settledResult != null ? settledResult : waitingResult(now);
            }

            settledResult = guardTransitionResult;

            if (guardTransitionResult.status() != EvaluationStatus.TRANSITION_FIRED) {
                return guardTransitionResult;
            }

            // back in the initial state, the token left the line: its run ends, the waiting run takes the next token
            if (!run.waiting() && activeStateMachine.initialState().equals(run.currentState)) {
                return guardTransitionResult;
            }
        }

        run.anomalyDetected = true;

        return anomalyResult(
                EvaluationStatus.AMBIGUOUS_TRANSITION,
                run.currentState,
                run.currentState,
                null,
                "More than " + MAX_CASCADED_TRANSITIONS_PER_OBSERVATION
                        + " transitions cascaded from a single observation without settling"
                        + " - this looks like a cycle in the state machine rather than a real"
                        + " sequence of transitions.");
    }

    private AnomalyEvaluationResult evaluateTimeoutTransitions(long now) {
        List<Transition> elapsedTimeTransitions = findElapsedRelativeTimeTransitions(now);

        if (elapsedTimeTransitions.size() == 1) {
            return fireTransition(elapsedTimeTransitions.get(0), now);
        }

        if (elapsedTimeTransitions.size() > 1) {
            run.anomalyDetected = true;

            return anomalyResult(
                    EvaluationStatus.AMBIGUOUS_TRANSITION,
                    run.currentState,
                    run.currentState,
                    null,
                    "Several timeout transitions are enabled from state '" + run.currentState + "'.");
        }

        return null;
    }

    private AnomalyEvaluationResult evaluateGuardTransitions(
            Map<String, Object> observedValues,
            long now) {
        List<Transition> enabledGuardTransitions = findEnabledGuardTransitions(observedValues);

        if (enabledGuardTransitions.size() == 1) {
            return fireTransition(enabledGuardTransitions.get(0), now);
        }

        if (enabledGuardTransitions.size() > 1) {
            run.anomalyDetected = true;

            return anomalyResult(
                    EvaluationStatus.AMBIGUOUS_TRANSITION,
                    run.currentState,
                    run.currentState,
                    null,
                    "Several guard-based transitions are enabled from state '" + run.currentState + "'.");
        }

        return null;
    }

    private AnomalyEvaluationResult waitingResult(long now) {
        Long remainingMillis = millisUntilNextRelativeTimeTransition(now);
        String message = "No complete guard is satisfied yet from state '" + run.currentState + "'.";

        if (remainingMillis != null) {
            message += " Timeout may occur in " + remainingMillis + " ms.";
        }

        return nonAnomalyResult(
                EvaluationStatus.WAITING_FOR_MATCH,
                run.currentState,
                run.currentState,
                null,
                message);
    }

    private List<Transition> relativeTimeTransitionsFromCurrentState() {
        List<Transition> transitions = new ArrayList<>();

        for (Transition transition : activeStateMachine.transitions()) {
            if (!transition.sourceState().equals(run.currentState)) {
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

    /** The enabled guard transitions of the run, except into a state another token is in (it must leave first). */
    private List<Transition> findEnabledGuardTransitions(Map<String, ?> observedValues) {
        return runtimeInterpreter.findEnabledGuardTransitions(
                        activeStateMachine, run.currentState, structuralModel, allStructuralModels, observedValues)
                .stream()
                .filter(transition -> !occupiedByAnotherToken(transition.targetState()))
                .toList();
    }

    private boolean occupiedByAnotherToken(String state) {
        if (activeStateMachine.initialState().equals(state)) {
            return false;
        }
        return runs.stream().anyMatch(other -> other != run && !other.anomalyDetected
                && state.equals(other.currentState));
    }

    private AnomalyEvaluationResult fireTransition(Transition firedTransition, long now) {
        String previousState = run.currentState;
        String targetState = firedTransition.targetState();

        if (RuntimeInterpreterUtils.isRelativeTimeTransition(firedTransition)) {
            run.anomalyDetected = true;

            return anomalyResult(
                    EvaluationStatus.ANOMALY_DETECTED,
                    previousState,
                    previousState,
                    firedTransition.name(),
                    "Timeout deviation. State '" + previousState
                            + "' remained active until relative-time transition '"
                            + firedTransition.name() + "' fired.");
        }

        run.currentState = targetState;
        run.stateEnteredAtMillis = now;

        addToTransitionHistory(run.currentState);

        logger.info("{}: transition '{}' fired: {} -> {}",
                describe(run), firedTransition.name(), previousState, run.currentState);

        return nonAnomalyResult(
                EvaluationStatus.TRANSITION_FIRED,
                previousState,
                run.currentState,
                firedTransition.name(),
                "Transition '" + firedTransition.name() + "' fired: "
                        + previousState + " -> " + run.currentState);
    }

    private boolean hasRelativeTimeElapsed(Transition transition, long now) {
        Long delayMillis = transition.delayMillis();

        if (delayMillis == null) {
            return false;
        }

        long elapsedMillis = now - run.stateEnteredAtMillis;
        return elapsedMillis >= delayMillis;
    }

    private Long millisUntilNextRelativeTimeTransition(long now) {
        Long shortestRemainingMillis = null;

        for (Transition transition : relativeTimeTransitionsFromCurrentState()) {
            Long delayMillis = transition.delayMillis();

            if (delayMillis == null) {
                continue;
            }

            long elapsedMillis = now - run.stateEnteredAtMillis;
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

        long elapsedMillis = now - run.stateEnteredAtMillis;

        for (ConstraintUsage constraintUsage : structuralModel.findConstraintUsagesByType("StateDurationLimit")) {

            String constrainedStateName = constraintUsage.inputValues().get("constrainedStateName");

            if (!RuntimeInterpreterUtils.valuesMatch(constrainedStateName, run.currentState)) {
                continue;
            }

            String maxDurationExpression = constraintUsage.inputValues().get("maxDuration");

            Long maxDurationMillis = RuntimeInterpreterUtils.parseDurationMillisOrNull(maxDurationExpression);

            if (maxDurationMillis == null) {
                continue;
            }

            if (elapsedMillis >= maxDurationMillis) {
                run.anomalyDetected = true;

                return anomalyResult(
                        EvaluationStatus.ANOMALY_DETECTED,
                        run.currentState,
                        run.currentState,
                        constraintUsage.name(),
                        "State duration constraint '" + constraintUsage.name()
                                + "' violated. State '" + run.currentState
                                + "' has been active for " + elapsedMillis
                                + " ms, but the maximum allowed duration is "
                                + maxDurationMillis + " ms.");
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

        List<String> history = run.transitionHistory;
        if (history.isEmpty() || !history.get(history.size() - 1).equals(state)) {
            history.add(state);
        }
    }

    private static String describe(TokenRun tokenRun) {
        return tokenRun.waiting() ? "waiting run" : "token #" + tokenRun.token;
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
            AnomalyContext anomalyContext) {
        return new AnomalyEvaluationResult(
                status,
                activeStateMachineId,
                activeStateMachineName(),
                previousState,
                currentState,
                transitionName,
                message,
                anomalyContext);
    }

    private AnomalyEvaluationResult anomalyResult(
            EvaluationStatus status,
            String previousState,
            String currentState,
            String transitionName,
            String message) {
        String affectedComponent = StateMachineInferenceUtils.inferAffectedComponent(activeStateMachine, previousState);

        if (affectedComponent == null) {
            logger.warn(
                    "Could not infer a unique affectedComponent from source state '{}' in state machine '{}'. Replanning will receive a null component.",
                    previousState,
                    activeStateMachineId);
        }

        String tokenMessage = run.waiting() ? message : "Token #" + run.token + ": " + message;

        String anomalyId = UUID.randomUUID().toString();
        anomalyRuns.put(anomalyId, run);

        AnomalyContext anomalyContext = new AnomalyContext(
                anomalyId,
                Instant.ofEpochMilli(clockMillis.getAsLong()),
                activeStateMachineName(),
                previousState,
                affectedComponent,
                previousState,
                currentState,
                tokenMessage);

        return buildResult(status, previousState, currentState, transitionName, tokenMessage, anomalyContext);
    }

    private AnomalyEvaluationResult nonAnomalyResult(
            EvaluationStatus status,
            String previousState,
            String currentState,
            String transitionName,
            String message) {
        return buildResult(status, previousState, currentState, transitionName, message, null);
    }
}
