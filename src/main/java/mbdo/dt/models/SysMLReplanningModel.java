package mbdo.dt.models;

import mbdo.dt.models.SysMLBehavioralModel.Condition;
import mbdo.dt.utils.RuntimeInterpreterUtils;
import mbdo.dt.utils.UnobservedAttributeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The replanning strategies ({@code part <name> : ReplanningStrategy { ... }}), mission migrations
 * ({@code state def <name> :> MissionMigration { ... }}) and machine stops ({@code state def <name> :> MachineStop
 * { ... }}) of the SysML models (see models/Replanning/replanning_def.sysml), as
 * {@link mbdo.dt.models.ReplanningElementExtractor} extracts them.
 */
public record SysMLReplanningModel(List<Strategy> strategies, Map<String, Migration> migrations,
                              Map<String, MachineStop> stops) {

    public SysMLReplanningModel(List<Strategy> strategies, Map<String, Migration> migrations) {
        this(strategies, migrations, Map.of());
    }

    /**
     * A reaction to an anomaly: stops the missions of the failed machines and migrates the other machines to
     * alternative missions.
     *
     * @param triggerConstraints the violated constraints that trigger it (e.g. "ConveyorBeltFeedDepartureLimit"), if any
     * @param triggerState      the monitored state in which the anomaly was detected that triggers it, or null
     * @param migrations        the names of the {@link Migration}s applied, in this order
     * @param recoveryStrategy  the strategy executed when the operator repaired the failure (back to the nominal
     *                          missions), or null
     */
    public record Strategy(
            String name,
            String description,
            List<String> triggerConstraints,
            String triggerState,
            List<String> stoppedMachines,
            List<String> migrations,
            String recoveryStrategy
    ) {
        boolean isTriggeredBy(String violatedConstraint, String anomalyState) {
            return triggerConstraints.contains(violatedConstraint)
                    || (triggerState != null && triggerState.equals(anomalyState));
        }
    }

    /**
     * Moves one SCADA machine from the mission it runs to an alternative mission.
     *
     * @param machine     the SCADA machine (logical name in the mission extension, e.g. "REN_MISSION_01/VGR01")
     * @param fromMission null: the machine runs no mission (e.g. it was stopped), {@code toMission} starts from its
     *                    initial state
     * @param rules       in the order of the model
     * @param waitStates  the states of {@code fromMission} the machine isn't migrated from: the replanning waits
     */
    public record Migration(
            String name,
            String machine,
            String fromMission,
            String toMission,
            List<Rule> rules,
            List<String> waitStates
    ) {
        /** The rules leaving {@code state}: those from it, and those from any state. */
        public List<Rule> rulesFrom(String state) {
            return rules.stream().filter(r -> r.fromState() == null || r.fromState().equals(state)).toList();
        }
    }

    /**
     * How a failed machine is stopped: between the stop of its mission and its stop command, it runs the command of
     * the first rule whose guard holds (the rule's {@code toState}), if any.
     *
     * @param direction the DIRECTION parameter of the commands, or null
     */
    public record MachineStop(String name, String machine, String direction, List<Rule> rules) {

        /** The rule whose command runs before the stop, if any. */
        public Optional<Rule> ruleBeforeStop(Map<String, ?> values) {
            return rules.stream().filter(r -> r.holds(values)).findFirst();
        }
    }

    /**
     * A migration rule: from {@code fromState} of the running mission (any state if null), when {@code guard} holds,
     * the alternative mission starts in {@code toState}.
     *
     * @param guard conditions on the digital twin's observations, all to hold (empty: always)
     */
    public record Rule(String name, String fromState, String toState, List<Condition> guard, String guardExpression) {

        /** Every condition holds; a condition on an attribute not observed yet doesn't. */
        public boolean holds(Map<String, ?> values) {
            for (Condition condition : guard) {
                try {
                    Object left = RuntimeInterpreterUtils.resolveRuntimeValue(condition.leftOperand(), values);
                    if (!RuntimeInterpreterUtils.evaluateRuntimeComparison(left, condition.operator(),
                            rightValue(condition.rightOperand(), values))) {
                        return false;
                    }
                } catch (UnobservedAttributeException e) {
                    return false;
                }
            }
            return true;
        }

        /** An attribute, a boolean or a number, or else a string: the parser already removed its quotes. */
        private static Object rightValue(String operand, Map<String, ?> values) {
            try {
                return RuntimeInterpreterUtils.resolveRuntimeValue(operand, values);
            } catch (UnobservedAttributeException e) {
                return operand;
            }
        }
    }

    /** How {@code machine} is stopped, if a MachineStop names it. */
    public Optional<MachineStop> findStop(String machine) {
        return stops.values().stream().filter(s -> s.machine().equals(machine)).findFirst();
    }

    public Optional<Strategy> findStrategy(String name) {
        return strategies.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** The first strategy triggered by the anomaly. */
    public Optional<Strategy> findTriggeredStrategy(String violatedConstraint, String anomalyState) {
        return strategies.stream().filter(s -> s.isTriggeredBy(violatedConstraint, anomalyState)).findFirst();
    }

    /** The replanning elements of every model, in their order. */
    public static SysMLReplanningModel merge(List<SysMLReplanningModel> models) {
        List<Strategy> strategies = new ArrayList<>();
        Map<String, Migration> migrations = new LinkedHashMap<>();
        Map<String, MachineStop> stops = new LinkedHashMap<>();
        for (SysMLReplanningModel model : models) {
            strategies.addAll(model.strategies());
            migrations.putAll(model.migrations());
            stops.putAll(model.stops());
        }
        return new SysMLReplanningModel(List.copyOf(strategies), Map.copyOf(migrations), Map.copyOf(stops));
    }
}
