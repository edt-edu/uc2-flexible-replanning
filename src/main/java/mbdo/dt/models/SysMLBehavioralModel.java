package mbdo.dt.models;

import java.util.List;

public record SysMLBehavioralModel(
        List<StateMachine> stateMachines
) {

    public record StateMachine(
            String name,
            String initialState,
            List<State> states,
            List<Transition> transitions
    ) {
    }

    public record State(
            String name
    ) {
    }

    public record Transition(
            String name,
            String sourceState,
            String targetState,
            TriggerKind triggerKind,
            String triggerExpression,
            Long delayMillis,
            List<Condition> conditions
    ) {
    }

    public record Condition(
            String leftOperand,
            Operator operator,
            String rightOperand,
            String rawExpression
    ) {
    }

    public enum TriggerKind {
        GUARD,
        CHANGE,
        RELATIVE_TIME
    }

    public enum Operator {
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        GREATER_THAN_OR_EQUALS,
        LESS_THAN,
        LESS_THAN_OR_EQUALS,
        UNKNOWN
    }
}