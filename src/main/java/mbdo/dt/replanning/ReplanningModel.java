package mbdo.dt.replanning;

import mbdo.dt.models.SysMLBehavioralModel.Condition;
import mbdo.dt.utils.RuntimeInterpreterUtils;
import mbdo.dt.utils.SysMLParserUtils;
import mbdo.dt.utils.UnobservedAttributeException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The replanning strategies ({@code part <name> : ReplanningStrategy { ... }}) and mission migrations
 * ({@code state def <name> :> MissionMigration { ... }}) of the SysML models (see
 * models/Replanning/replanning_def.sysml).
 */
public record ReplanningModel(List<Strategy> strategies, Map<String, Migration> migrations) {

    /**
     * A reaction to an anomaly: stops the missions of the failed machines and migrates the other machines to
     * alternative missions.
     *
     * @param triggerConstraint the violated constraint that triggers it (e.g. "ConveyorBeltFeedDepartureLimit"), or null
     * @param triggerState      the monitored state in which the anomaly was detected that triggers it, or null
     * @param migrations        the names of the {@link Migration}s applied, in this order
     */
    public record Strategy(
            String name,
            String description,
            String triggerConstraint,
            String triggerState,
            List<String> stoppedMachines,
            List<String> migrations
    ) {
        boolean isTriggeredBy(String violatedConstraint, String anomalyState) {
            return (triggerConstraint != null && triggerConstraint.equals(violatedConstraint))
                    || (triggerState != null && triggerState.equals(anomalyState));
        }
    }

    /**
     * Moves one SCADA machine from the mission it runs to an alternative mission.
     *
     * @param machine    the SCADA machine (logical name in the mission extension, e.g. "REN_MISSION_01/VGR01")
     * @param rules      in the order of the model
     * @param waitStates the states of {@code fromMission} the machine isn't migrated from: the replanning waits
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

    public Optional<Strategy> findStrategy(String name) {
        return strategies.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** The first strategy triggered by the anomaly. */
    public Optional<Strategy> findTriggeredStrategy(String violatedConstraint, String anomalyState) {
        return strategies.stream().filter(s -> s.isTriggeredBy(violatedConstraint, anomalyState)).findFirst();
    }

    // ---- parsing ----

    private static final String QUALIFIED_NAME = "[A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*";
    private static final Pattern STRATEGY = Pattern.compile(
            "\\bpart\\s+([A-Za-z_]\\w*)\\s*:\\s*(?:\\w+::)*ReplanningStrategy\\s*\\{");
    private static final Pattern MIGRATION = Pattern.compile(
            "\\bstate\\s+def\\s+([A-Za-z_]\\w*)\\s*:>\\s*(?:\\w+::)*MissionMigration\\s*\\{");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\battribute\\s+(?::>>\\s*)?([A-Za-z_]\\w*)\\s*(?::[^=;{]+)?=\\s*(\"[^\"]*\"|\\([^)]*\\)|[^;]+);");
    /** transition [name] [first State] [accept when guard] then State; */
    private static final Pattern TRANSITION = Pattern.compile(
            "\\btransition\\s+(?:(?!first\\b|accept\\b|then\\b)([A-Za-z_]\\w*)\\s+)?"
                    + "(?:first\\s+(" + QUALIFIED_NAME + ")\\s+)?"
                    + "(?:accept\\s+when\\s+(.+?)\\s+)?"
                    + "then\\s+(" + QUALIFIED_NAME + ")\\s*;", Pattern.DOTALL);
    private static final Pattern STRING_ITEM = Pattern.compile("\"([^\"]*)\"");

    /**
     * Every replanning element of the SysML files matching {@code pattern} (as {@code sysml.models.path}).
     *
     * @throws IllegalArgumentException if a file has an invalid replanning element
     */
    public static ReplanningModel load(String pattern) {
        List<Strategy> strategies = new ArrayList<>();
        Map<String, Migration> migrations = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                ReplanningModel model;
                try {
                    model = parse(resource.getContentAsString(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(resource.getDescription() + ": " + e.getMessage(), e);
                }
                strategies.addAll(model.strategies());
                migrations.putAll(model.migrations());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ReplanningModel(List.copyOf(strategies), Map.copyOf(migrations));
    }

    public static ReplanningModel parse(String sysml) {
        String content = SysMLParserUtils.cleanContent(sysml);

        List<Strategy> strategies = new ArrayList<>();
        Matcher strategy = STRATEGY.matcher(content);
        while (strategy.find()) {
            Map<String, String> attributes = attributes(blockBody(content, strategy.end() - 1));
            strategies.add(new Strategy(
                    strategy.group(1),
                    SysMLParserUtils.cleanValue(attributes.get("description")),
                    SysMLParserUtils.cleanValue(attributes.get("triggerConstraint")),
                    SysMLParserUtils.cleanValue(attributes.get("triggerState")),
                    stringList(attributes.get("stoppedMachines")),
                    stringList(attributes.get("migrations"))));
        }

        Map<String, Migration> migrations = new LinkedHashMap<>();
        Matcher migration = MIGRATION.matcher(content);
        while (migration.find()) {
            String name = migration.group(1);
            String body = blockBody(content, migration.end() - 1);
            Map<String, String> attributes = attributes(body);
            String fromMission = require(attributes, "fromMission", name);
            String toMission = require(attributes, "toMission", name);

            List<Rule> rules = new ArrayList<>();
            Matcher transition = TRANSITION.matcher(body);
            while (transition.find()) {
                String fromState = transition.group(2) == null ? null : stateOf(transition.group(2), fromMission, name);
                String toState = stateOf(transition.group(4), toMission, name);
                String guard = transition.group(3) == null ? "" : SysMLParserUtils.normalizeWhitespace(transition.group(3));
                rules.add(new Rule(
                        transition.group(1) != null ? transition.group(1) : (fromState == null ? "*" : fromState) + "->" + toState,
                        fromState,
                        toState,
                        SysMLParserUtils.splitTopLevelAnd(guard).stream()
                                .filter(clause -> !clause.isBlank())
                                .map(SysMLParserUtils::parseCondition)
                                .toList(),
                        guard));
            }
            migrations.put(name, new Migration(name, require(attributes, "machine", name), fromMission, toMission,
                    List.copyOf(rules), stringList(attributes.get("waitStates"))));
        }
        return new ReplanningModel(List.copyOf(strategies), migrations);
    }

    /** "Mission::State" or "State": the state, checking the mission if given. */
    private static String stateOf(String qualifiedState, String expectedMission, String migration) {
        String[] parts = qualifiedState.split("::");
        if (parts.length > 1 && !parts[parts.length - 2].equals(expectedMission)) {
            throw new IllegalArgumentException("Migration " + migration + ": state " + qualifiedState
                    + " is not a state of " + expectedMission);
        }
        return parts[parts.length - 1];
    }

    private static Map<String, String> attributes(String body) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(body);
        while (matcher.find()) {
            attributes.put(matcher.group(1), matcher.group(2).trim());
        }
        return attributes;
    }

    private static List<String> stringList(String value) {
        List<String> items = new ArrayList<>();
        if (value != null) {
            Matcher matcher = STRING_ITEM.matcher(value);
            while (matcher.find()) {
                items.add(matcher.group(1));
            }
        }
        return List.copyOf(items);
    }

    private static String require(Map<String, String> attributes, String name, String migration) {
        String value = SysMLParserUtils.cleanValue(attributes.get(name));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Migration " + migration + " has no attribute " + name);
        }
        return value;
    }

    private static String blockBody(String content, int openBrace) {
        int close = SysMLParserUtils.findMatchingBrace(content, openBrace);
        if (close < 0) {
            throw new IllegalArgumentException("Unclosed block at " + openBrace);
        }
        return content.substring(openBrace + 1, close);
    }
}
