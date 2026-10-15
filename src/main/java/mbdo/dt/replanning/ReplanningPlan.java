package mbdo.dt.replanning;

import java.util.List;

/**
 * What a replanning strategy does to the SCADA machines, as the incident shows it: one step per stopped machine, then
 * one per migrated machine.
 *
 * @param problems why it waits (e.g. a machine in a state it isn't migrated from), empty if it can be executed
 * @param message  the outcome of the execution, null before
 */
public record ReplanningPlan(
        String strategy,
        String description,
        String anomalyId,
        Status status,
        List<Step> steps,
        List<String> problems,
        String message
) {

    public enum Status {
        /** can't be executed yet (see the problems): planned again until it can */
        WAITING,
        /** can be executed */
        PLANNED,
        EXECUTING,
        EXECUTED,
        FAILED
    }

    /**
     * @param rule   the migration rule applied, "same state" if none, null for a stop
     * @param detail what happens (or happened, or why it failed)
     */
    public record Step(
            Kind kind,
            String machine,
            String rule,
            String fromMission,
            String fromState,
            String toMission,
            String toState,
            StepStatus status,
            String detail
    ) {
        public Step with(StepStatus newStatus, String newDetail) {
            return new Step(kind, machine, rule, fromMission, fromState, toMission, toState, newStatus, newDetail);
        }
    }

    public enum Kind {
        /** stop the mission of a failed machine, then the command it executes */
        STOP_MISSION,
        /** swap the machine to the alternative mission */
        MIGRATE
    }

    public enum StepStatus {
        PENDING,
        DONE,
        FAILED,
        SKIPPED
    }

    public ReplanningPlan with(Status newStatus, List<Step> newSteps, String newMessage) {
        return new ReplanningPlan(strategy, description, anomalyId, newStatus, List.copyOf(newSteps), problems,
                newMessage);
    }
}
