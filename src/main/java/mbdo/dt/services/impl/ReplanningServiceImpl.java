package mbdo.dt.services.impl;

import jakarta.annotation.PostConstruct;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.incident.ReplanningIncidentRegistryService;
import mbdo.dt.incident.ReplanningIncidentSseService;
import mbdo.dt.replanning.ReplanningModel;
import mbdo.dt.replanning.ReplanningModel.Migration;
import mbdo.dt.replanning.ReplanningModel.Rule;
import mbdo.dt.replanning.ReplanningModel.Strategy;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.replanning.ReplanningPlan.Kind;
import mbdo.dt.replanning.ReplanningPlan.Status;
import mbdo.dt.replanning.ReplanningPlan.Step;
import mbdo.dt.replanning.ReplanningPlan.StepStatus;
import mbdo.dt.replanning.scada.ScadaClient;
import mbdo.dt.replanning.scada.ScadaCommandResult;
import mbdo.dt.replanning.scada.ScadaConflictException;
import mbdo.dt.replanning.scada.ScadaException;
import mbdo.dt.replanning.scada.ScadaMachine;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;
import mbdo.dt.services.ReplanningService;
import mbdo.dt.services.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reacts to an anomaly with the replanning strategy it triggers, without the operator's approval:
 * <ol>
 *     <li>plans it on the missions and states the SCADA machines are in: each migrated machine goes to the target
 *     state of the first rule leaving its state whose guard holds, or else to the state of the same name;</li>
 *     <li>until every machine can be migrated (e.g. a gripper still over the belt), plans again every
 *     {@code retryDelayMs}, for at most {@code retryMaxMs}, while the anomaly's incident is open;</li>
 *     <li>executes it: stops the failed machines (their mission, then the command they execute), then swaps each
 *     migrated machine's mission atomically. A machine that moved on meanwhile makes it plan again.</li>
 * </ol>
 * The plan is attached to the anomaly's incident at each step, for the operator to follow.
 */
@Service
public class ReplanningServiceImpl implements ReplanningService {

    private static final Logger logger = LoggerFactory.getLogger(ReplanningServiceImpl.class);

    /** The monitored token location, for the guards of the migration rules: the state of the anomaly's token. */
    public static final String TOKEN_LOCATION_STATE = "tokenLocation.state";
    /** The command stopping a machine (every machine has one): the PLC interrupts the command in progress. */
    private static final String STOP_COMMAND = "stop";

    private final ScadaClient scada;
    private final ReplanningIncidentRegistryService incidents;
    private final ReplanningIncidentSseService incidentEvents;
    private final RuntimeContext runtimeContext;
    private final String modelsPath;
    private final long retryDelayMs;
    private final long retryMaxMs;
    private final long commandTimeoutMs;

    private volatile ReplanningModel model = new ReplanningModel(List.of(), Map.of());
    /** The state of the token of each anomaly, when it was detected: other tokens move on meanwhile. */
    private final Map<String, String> anomalyTokenStates = new ConcurrentHashMap<>();
    /** Talks to the SCADA away from the anomaly detection, which must not wait for it. One replanning at a time. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "replanning");
        thread.setDaemon(true);
        return thread;
    });

    public ReplanningServiceImpl(
            ScadaClient scada,
            ReplanningIncidentRegistryService incidents,
            ReplanningIncidentSseService incidentEvents,
            RuntimeContext runtimeContext,
            @Value("${sysml.models.path:}") String modelsPath,
            @Value("${replanning.retry-delay-ms:1000}") long retryDelayMs,
            @Value("${replanning.retry-max-ms:60000}") long retryMaxMs,
            @Value("${replanning.command-timeout-ms:60000}") long commandTimeoutMs
    ) {
        this.scada = scada;
        this.incidents = incidents;
        this.incidentEvents = incidentEvents;
        this.runtimeContext = runtimeContext;
        this.modelsPath = modelsPath;
        this.retryDelayMs = retryDelayMs;
        this.retryMaxMs = retryMaxMs;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    @PostConstruct
    void loadModel() {
        if (modelsPath == null || modelsPath.isBlank()) {
            logger.warn("No SysML model path configured: no replanning strategy.");
            return;
        }
        String pattern = modelsPath.contains("*") || modelsPath.endsWith(".sysml")
                ? modelsPath
                : (modelsPath.endsWith("/") ? modelsPath : modelsPath + "/") + "**/*.sysml";
        try {
            model = ReplanningModel.load(pattern);
        } catch (RuntimeException e) {
            logger.error("Invalid replanning model, no replanning strategy: {}", e.getMessage());
            return;
        }
        for (Strategy strategy : model.strategies()) {
            logger.info("Replanning strategy {}: stops {}, migrations {}", strategy.name(),
                    strategy.stoppedMachines(), strategy.migrations());
            strategy.migrations().stream().filter(m -> !model.migrations().containsKey(m)).forEach(missing ->
                    logger.error("Replanning strategy {} names an undefined migration {}", strategy.name(), missing));
        }
    }

    @Override
    public ReplanningModel getModel() {
        return model;
    }

    @Override
    public void handleAnomaly(
            DataRequest triggeringRequest,
            AnomalyEvaluationResult anomalyResult,
            Map<String, Object> latestRuntimeValues
    ) {
        if (anomalyResult == null || anomalyResult.anomalyContext() == null) {
            return;
        }
        AnomalyContext context = anomalyResult.anomalyContext();
        Strategy strategy = model.findTriggeredStrategy(anomalyResult.transitionName(), context.affectedStep())
                .orElse(null);
        if (strategy == null) {
            logger.info("No replanning strategy for anomaly '{}' (constraint {}, state {}).",
                    context.anomalyId(), anomalyResult.transitionName(), context.affectedStep());
            return;
        }
        if (anomalyResult.currentState() != null) {
            anomalyTokenStates.put(context.anomalyId(), anomalyResult.currentState());
        }
        Map<String, Object> values = new HashMap<>(latestRuntimeValues == null ? Map.of() : latestRuntimeValues);
        values.put(TOKEN_LOCATION_STATE, anomalyResult.currentState());
        logger.info("Anomaly '{}' triggers replanning strategy {}.", context.anomalyId(), strategy.name());
        worker.submit(() -> replan(strategy, context.anomalyId(), values));
    }

    @Override
    public ReplanningPlan planStrategy(String strategyName) {
        Strategy strategy = model.findStrategy(strategyName)
                .orElseThrow(() -> new IllegalArgumentException("No replanning strategy " + strategyName));
        return plan(strategy, model, scada.machines(), currentValues(null), null);
    }

    /** Plans the strategy on {@code values}, then on the latest observations until it can be executed. */
    private void replan(Strategy strategy, String anomalyId, Map<String, ?> values) {
        long deadline = System.currentTimeMillis() + retryMaxMs;
        while (true) {
            ReplanningPlan plan;
            try {
                plan = plan(strategy, model, scada.machines(), values, anomalyId);
            } catch (ScadaException e) {
                plan = new ReplanningPlan(strategy.name(), strategy.description(), anomalyId, Status.WAITING,
                        List.of(), List.of(e.getMessage()), null);
            }
            if (!publish(plan)) {
                logger.info("Replanning {} dropped: its incident is no longer open", strategy.name());
                return;
            }
            if (plan.status() != Status.WAITING) {
                try {
                    execute(plan);
                    return;
                } catch (ScadaConflictException e) {
                    logger.info("Replanning {}: a machine moved on ({}), planning again", strategy.name(),
                            e.getMessage());
                }
            }
            if (System.currentTimeMillis() + retryDelayMs > deadline) {
                logger.warn("Replanning {} given up after {} ms: {}", strategy.name(), retryMaxMs, plan.problems());
                publish(plan.with(Status.FAILED, plan.steps(), "Given up after " + retryMaxMs + " ms"));
                return;
            }
            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            values = currentValues(anomalyId);
        }
    }

    // ---- planning: pure ----

    static ReplanningPlan plan(Strategy strategy, ReplanningModel model, List<ScadaMachine> machines,
                               Map<String, ?> values, String anomalyId) {
        List<Step> steps = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (String name : strategy.stoppedMachines()) {
            Optional<ScadaMachine> machine = find(machines, name);
            if (machine.isEmpty()) {
                problems.add("Machine " + name + " is unknown to the SCADA");
                continue;
            }
            // the machine is stopped even if it runs no mission: it may still execute a command (a belt moving)
            String mission = machine.get().activeMission();
            steps.add(new Step(Kind.STOP_MISSION, name, null, mission, machine.get().activeState(), null, null,
                    StepStatus.PENDING, (mission == null ? "runs no mission" : "stop " + mission)
                    + ", then send it a stop command"));
        }

        for (String name : strategy.migrations()) {
            Migration migration = model.migrations().get(name);
            if (migration == null) {
                problems.add("Migration " + name + " is not defined in the models");
                continue;
            }
            Optional<ScadaMachine> machine = find(machines, migration.machine());
            if (machine.isEmpty()) {
                problems.add("Machine " + migration.machine() + " is unknown to the SCADA");
                continue;
            }
            Step step = migrationStep(migration, machine.get(), values);
            if (step.status() == StepStatus.FAILED) {
                problems.add(step.detail());
            } else {
                steps.add(step);
            }
        }

        return new ReplanningPlan(strategy.name(), strategy.description(), anomalyId,
                problems.isEmpty() ? Status.PLANNED : Status.WAITING, List.copyOf(steps), List.copyOf(problems),
                null);
    }

    /** The migration of a machine, FAILED (with why) if it can't be migrated from its mission and state. */
    private static Step migrationStep(Migration migration, ScadaMachine machine, Map<String, ?> values) {
        String state = machine.activeState();
        Step step = new Step(Kind.MIGRATE, machine.name(), null, machine.activeMission(), state,
                migration.toMission(), null, StepStatus.FAILED, null);

        if (migration.toMission().equals(machine.activeMission())) {
            return step.with(StepStatus.SKIPPED, "already runs " + migration.toMission());
        }
        if (!migration.fromMission().equals(machine.activeMission())) {
            return step.with(StepStatus.FAILED, machine.name() + " runs "
                    + (machine.activeMission() == null ? "no mission" : machine.activeMission())
                    + ", migration " + migration.name() + " starts from " + migration.fromMission());
        }
        if (migration.waitStates().contains(state)) {
            return step.with(StepStatus.FAILED, machine.name() + " is in " + state + ": waiting for it to leave it");
        }

        List<Rule> leaving = migration.rulesFrom(state);
        Optional<Rule> rule = leaving.stream().filter(r -> r.holds(values)).findFirst();
        if (!leaving.isEmpty() && rule.isEmpty()) {
            return step.with(StepStatus.FAILED, "Migration " + migration.name() + ": no guard holds from " + state
                    + " " + leaving.stream().map(r -> r.name() + ": " + r.guardExpression()).toList());
        }
        // no rule leaves the state: the alternative mission starts in the state of the same name
        String toState = rule.map(Rule::toState).orElse(state);
        String ruleName = rule.map(Rule::name).orElse("same state");
        if (!machine.hasState(migration.toMission(), toState)) {
            return step.with(StepStatus.FAILED, "Migration " + migration.name() + " (" + ruleName + "): "
                    + migration.toMission() + " has no state " + toState + " in the SCADA");
        }
        return new Step(Kind.MIGRATE, machine.name(), ruleName, migration.fromMission(), state,
                migration.toMission(), toState, StepStatus.PENDING,
                "swap to " + migration.toMission() + "::" + toState);
    }

    private static Optional<ScadaMachine> find(List<ScadaMachine> machines, String name) {
        return machines.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    // ---- execution ----

    /**
     * Executes the steps one after the other; the first that fails ends it.
     *
     * @throws ScadaConflictException if a machine moved on since the plan was computed: plan again
     */
    ReplanningPlan execute(ReplanningPlan plan) {
        List<Step> steps = new ArrayList<>(plan.steps());
        publish(plan.with(Status.EXECUTING, steps, null));
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.status() != StepStatus.PENDING) {
                continue;
            }
            try {
                steps.set(i, step.with(StepStatus.DONE, step.kind() == Kind.STOP_MISSION ? stop(step) : swap(step)));
            } catch (ScadaConflictException e) {
                throw e;
            } catch (ScadaException e) {
                steps.set(i, step.with(StepStatus.FAILED, e.getMessage()));
                for (int j = i + 1; j < steps.size(); j++) {
                    if (steps.get(j).status() == StepStatus.PENDING) {
                        steps.set(j, steps.get(j).with(StepStatus.SKIPPED, "not executed: a previous step failed"));
                    }
                }
                logger.warn("Replanning {} failed on {}: {}", plan.strategy(), step.machine(), e.getMessage());
                ReplanningPlan failed = plan.with(Status.FAILED, steps,
                        "Failed on " + step.machine() + ": " + e.getMessage());
                publish(failed);
                return failed;
            }
            logger.info("Replanning {}: {} {} ({})", plan.strategy(), step.kind(), step.machine(),
                    steps.get(i).detail());
            publish(plan.with(Status.EXECUTING, steps, null));
        }
        ReplanningPlan executed = plan.with(Status.EXECUTED, steps, "Replanning " + plan.strategy() + " executed");
        publish(executed);
        return executed;
    }

    private String stop(Step step) {
        scada.stopMission(step.machine(), null, null);
        String stopped = step.fromMission() == null ? "runs no mission" : "stopped " + step.fromMission();
        ScadaCommandResult result = scada.executeCommand(step.machine(), STOP_COMMAND, List.of(), commandTimeoutMs);
        if (!result.done()) {
            throw new ScadaException(stopped + ", but its stop command ended with " + result.status());
        }
        return stopped + ", machine stopped";
    }

    private String swap(Step step) {
        scada.swapMission(step.machine(), step.toMission(), step.toState(), step.fromMission(), step.fromState());
        return "from " + step.fromMission() + "::" + step.fromState() + " to " + step.toMission() + "::"
                + step.toState() + " (" + step.rule() + ")";
    }

    /** Attaches the plan to its anomaly's incident: false if that incident is no longer open. */
    private boolean publish(ReplanningPlan plan) {
        if (plan.anomalyId() == null) {
            return true;
        }
        return incidents.attachPlan(plan.anomalyId(), plan).map(incident -> {
            incidentEvents.broadcastUpdated(incident);
            return true;
        }).orElse(false);
    }

    /** The latest observations, with the state of the anomaly's token (of the most recent token without anomaly). */
    private Map<String, ?> currentValues(String anomalyId) {
        Map<String, Object> values = new HashMap<>(runtimeContext.snapshot());
        String tokenState = anomalyId == null ? null : anomalyTokenStates.get(anomalyId);
        values.put(TOKEN_LOCATION_STATE, tokenState != null ? tokenState : runtimeContext.getCurrentState());
        return values;
    }
}
