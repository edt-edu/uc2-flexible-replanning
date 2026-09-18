package mbdo.dt.utils;

import mbdo.dt.models.SysMLBehavioralModel;
import mbdo.dt.models.SysMLBehavioralModel.StateMachine;
import mbdo.dt.models.SysMLBehavioralModel.Transition;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StateMachineInferenceUtils {

    private static final Pattern COMPONENT_REFERENCE_PATTERN =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.");

    private StateMachineInferenceUtils() {
    }

    public static String inferAffectedComponent(StateMachine stateMachine, String sourceState) {
        if (stateMachine == null || sourceState == null || sourceState.isBlank()) {
            return null;
        }

        Set<String> incomingComponents = componentPrefixesFromIncomingTransitions(stateMachine, sourceState);
        Set<String> outgoingComponents = componentPrefixesFromOutgoingTransitions(stateMachine, sourceState);

        Set<String> commonComponents = new LinkedHashSet<>(incomingComponents);
        commonComponents.retainAll(outgoingComponents);

        if (commonComponents.size() == 1) {
            return commonComponents.iterator().next();
        }

        if (!incomingComponents.isEmpty() && !outgoingComponents.isEmpty()) {
            return null;
        }

        if (incomingComponents.size() == 1) {
            return incomingComponents.iterator().next();
        }

        if (outgoingComponents.size() == 1) {
            return outgoingComponents.iterator().next();
        }

        return null;
    }

    private static Set<String> componentPrefixesFromIncomingTransitions(
            StateMachine stateMachine,
            String stateName
    ) {
        Set<String> components = new LinkedHashSet<>();

        if (stateMachine.transitions() == null) {
            return components;
        }

        for (Transition transition : stateMachine.transitions()) {
            if (!stateName.equals(transition.targetState())) {
                continue;
            }

            components.addAll(componentPrefixesFromTransition(transition));
        }

        return components;
    }

    private static Set<String> componentPrefixesFromOutgoingTransitions(
            StateMachine stateMachine,
            String stateName
    ) {
        Set<String> components = new LinkedHashSet<>();

        if (stateMachine.transitions() == null) {
            return components;
        }

        for (Transition transition : stateMachine.transitions()) {
            if (!stateName.equals(transition.sourceState())) {
                continue;
            }

            components.addAll(componentPrefixesFromTransition(transition));
        }

        return components;
    }

    private static Set<String> componentPrefixesFromTransition(Transition transition) {
        Set<String> components = new LinkedHashSet<>();

        if (transition == null || transition.conditions() == null) {
            return components;
        }

        for (SysMLBehavioralModel.Condition condition : transition.conditions()) {
            if (condition != null) {
                addComponentPrefixesFromExpression(components, condition.rawExpression());
            }
        }

        return components;
    }

    private static void addComponentPrefixesFromExpression(
            Set<String> components,
            String expression
    ) {
        if (expression == null || expression.isBlank()) {
            return;
        }

        Matcher matcher = COMPONENT_REFERENCE_PATTERN.matcher(expression);

        while (matcher.find()) {
            components.add(matcher.group(1));
        }
    }
}