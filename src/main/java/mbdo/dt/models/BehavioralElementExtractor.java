package mbdo.dt.models;

import mbdo.dt.models.SysMLBehavioralModel.Condition;
import mbdo.dt.models.SysMLBehavioralModel.State;
import mbdo.dt.models.SysMLBehavioralModel.Transition;
import mbdo.dt.utils.SysMLParserUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the behavioral elements of a single SysML state machine body - its initial state,
 * the states it declares, and the transitions between them (including trigger kind, delay and
 * guard conditions). Stateless: every call is given the state machine block text it should read.
 */
@Component
public class BehavioralElementExtractor {

    public String extractInitialState(String body) {
        String normalizedBody = SysMLParserUtils.normalizeWhitespace(body);

        String marker = "entry; then ";
        int markerIndex = normalizedBody.indexOf(marker);

        if (markerIndex < 0) {
            return null;
        }

        int nameStart = markerIndex + marker.length();
        int nameEnd = normalizedBody.indexOf(';', nameStart);

        if (nameEnd < 0) {
            return null;
        }

        String initialState = normalizedBody.substring(nameStart, nameEnd).trim();

        return initialState.isBlank() ? null : initialState;
    }

    public List<State> extractStates(String body) {
        Set<String> stateNames = new LinkedHashSet<>();

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();

            if (!line.startsWith("state ") || line.startsWith("state def ")) {
                continue;
            }

            if (line.contains(":")) {
                continue;
            }

            String name = line.substring("state ".length())
                    .replace(";", "")
                    .replace("{", "")
                    .replace("}", "")
                    .trim();

            if (!name.isBlank()) {
                stateNames.add(name);
            }
        }

        List<State> states = new ArrayList<>();

        for (String stateName : stateNames) {
            states.add(new State(stateName));
        }

        return states;
    }

    public List<Transition> extractTransitions(String body) {
        List<Transition> transitions = new ArrayList<>();

        String identifier = SysMLParserUtils.identifierRegex();
        String qualifiedName = SysMLParserUtils.qualifiedNameRegex();

        Pattern pattern = Pattern.compile(
                "\\btransition\\s+(" + identifier + ")\\s+"
                        + "first\\s+(" + qualifiedName + ")\\s+"
                        + "(.*?)\\s+"
                        + "then\\s+(" + qualifiedName + ")\\s*;",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(body);

        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String sourceState = matcher.group(2).trim();
            String middle = SysMLParserUtils.normalizeWhitespace(matcher.group(3));
            String targetState = matcher.group(4).trim();

            ParsedTransitionMiddle parsedMiddle = parseTransitionMiddle(middle);

            Long delayMillis = null;

            if (parsedMiddle.triggerKind() == SysMLBehavioralModel.TriggerKind.RELATIVE_TIME) {
                delayMillis = SysMLParserUtils.parseDurationMillis(parsedMiddle.guardExpression());
            }

            List<Condition> conditions =
                    parsedMiddle.triggerKind() == SysMLBehavioralModel.TriggerKind.RELATIVE_TIME
                            ? List.of()
                            : extractConditions(parsedMiddle.guardExpression());

            transitions.add(new Transition(
                    name,
                    sourceState,
                    targetState,
                    parsedMiddle.triggerKind(),
                    parsedMiddle.triggerExpression(),
                    delayMillis,
                    conditions
            ));
        }

        return transitions;
    }

    private ParsedTransitionMiddle parseTransitionMiddle(String middle) {
        String normalized = SysMLParserUtils.normalizeWhitespace(middle);

        if (SysMLParserUtils.startsWithIgnoreCase(normalized, "accept after ")) {
            String delay = normalized.substring("accept after ".length()).trim();
            return new ParsedTransitionMiddle(
                    SysMLBehavioralModel.TriggerKind.RELATIVE_TIME, delay, delay);
        }

        if (SysMLParserUtils.startsWithIgnoreCase(normalized, "accept when ")) {
            String guard = normalized.substring("accept when ".length()).trim();
            return new ParsedTransitionMiddle(
                    SysMLBehavioralModel.TriggerKind.CHANGE, guard,
                    SysMLParserUtils.removeDoActionBlock(guard));
        }

        if (SysMLParserUtils.startsWithIgnoreCase(normalized, "accept ")) {
            String rest = normalized.substring("accept ".length()).trim();
            int ifIndex = SysMLParserUtils.indexOfStandaloneIf(rest);
            if (ifIndex >= 0) {
                String eventName = rest.substring(0, ifIndex).trim();
                String guard = rest.substring(ifIndex + " if ".length()).trim();
                return new ParsedTransitionMiddle(
                        SysMLBehavioralModel.TriggerKind.GUARD, eventName,
                        SysMLParserUtils.removeDoActionBlock(guard));
            }
            return new ParsedTransitionMiddle(
                    SysMLBehavioralModel.TriggerKind.GUARD, rest, "");
        }

        if (SysMLParserUtils.startsWithIgnoreCase(normalized, "if ")) {
            String guard = normalized.substring("if ".length()).trim();
            return new ParsedTransitionMiddle(
                    SysMLBehavioralModel.TriggerKind.GUARD, guard,
                    SysMLParserUtils.removeDoActionBlock(guard));
        }

        return new ParsedTransitionMiddle(
                SysMLBehavioralModel.TriggerKind.GUARD, normalized,
                SysMLParserUtils.removeDoActionBlock(normalized));
    }

    private List<Condition> extractConditions(String guardExpression) {
        List<Condition> conditions = new ArrayList<>();

        if (guardExpression == null || guardExpression.isBlank()) {
            return conditions;
        }

        for (String clause : SysMLParserUtils.splitTopLevelAnd(guardExpression)) {
            if (!clause.isBlank()) {
                conditions.add(SysMLParserUtils.parseCondition(clause));
            }
        }

        return conditions;
    }

    private record ParsedTransitionMiddle(
            SysMLBehavioralModel.TriggerKind triggerKind,
            String triggerExpression,
            String guardExpression
    ) {
    }
}
