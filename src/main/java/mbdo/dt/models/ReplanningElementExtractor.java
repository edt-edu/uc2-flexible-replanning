package mbdo.dt.models;

import mbdo.dt.models.SysMLReplanningModel.MachineStop;
import mbdo.dt.models.SysMLReplanningModel.Migration;
import mbdo.dt.models.SysMLReplanningModel.Rule;
import mbdo.dt.models.SysMLReplanningModel.Strategy;
import mbdo.dt.utils.SysMLParserUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the replanning elements of a SysML model (see models/Replanning/replanning_def.sysml): the replanning
 * strategies ({@code part <name> : ReplanningStrategy { ... }}), mission migrations
 * ({@code state def <name> :> MissionMigration { ... }}) and machine stops ({@code state def <name> :> MachineStop
 * { ... }}).
 */
@Component
public class ReplanningElementExtractor {

    private static final String QUALIFIED_NAME = "[A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*";
    private static final Pattern STRATEGY = Pattern.compile(
            "\\bpart\\s+([A-Za-z_]\\w*)\\s*:\\s*(?:\\w+::)*ReplanningStrategy\\s*\\{");
    private static final Pattern MIGRATION = Pattern.compile(
            "\\bstate\\s+def\\s+([A-Za-z_]\\w*)\\s*:>\\s*(?:\\w+::)*MissionMigration\\s*\\{");
    private static final Pattern STOP = Pattern.compile(
            "\\bstate\\s+def\\s+([A-Za-z_]\\w*)\\s*:>\\s*(?:\\w+::)*MachineStop\\s*\\{");
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
     * The replanning elements of a SysML model.
     *
     * @param content the model, cleaned ({@link SysMLParserUtils#cleanContent})
     * @throws IllegalArgumentException if a replanning element is invalid
     */
    public SysMLReplanningModel extract(String content) {

        List<Strategy> strategies = new ArrayList<>();
        Matcher strategy = STRATEGY.matcher(content);
        while (strategy.find()) {
            Map<String, String> attributes = attributes(blockBody(content, strategy.end() - 1));
            strategies.add(new Strategy(
                    strategy.group(1),
                    SysMLParserUtils.cleanValue(attributes.get("description")),
                    stringList(attributes.get("triggerConstraint")),
                    SysMLParserUtils.cleanValue(attributes.get("triggerState")),
                    stringList(attributes.get("stoppedMachines")),
                    stringList(attributes.get("migrations")),
                    SysMLParserUtils.cleanValue(attributes.get("recoveryStrategy"))));
        }

        Map<String, Migration> migrations = new LinkedHashMap<>();
        Matcher migration = MIGRATION.matcher(content);
        while (migration.find()) {
            String name = migration.group(1);
            String body = blockBody(content, migration.end() - 1);
            Map<String, String> attributes = attributes(body);
            String fromMission = SysMLParserUtils.cleanValue(attributes.get("fromMission"));
            String toMission = require(attributes, "toMission", name);

            List<Rule> rules = rules(body, fromMission, toMission, name);
            migrations.put(name, new Migration(name, require(attributes, "machine", name), fromMission, toMission,
                    List.copyOf(rules), stringList(attributes.get("waitStates"))));
        }

        Map<String, MachineStop> stops = new LinkedHashMap<>();
        Matcher stop = STOP.matcher(content);
        while (stop.find()) {
            String name = stop.group(1);
            String body = blockBody(content, stop.end() - 1);
            Map<String, String> attributes = attributes(body);
            // a rule's "then" names the command: no mission to check it against
            stops.put(name, new MachineStop(name, require(attributes, "machine", name),
                    SysMLParserUtils.cleanValue(attributes.get("direction")), rules(body, null, null, name)));
        }
        return new SysMLReplanningModel(List.copyOf(strategies), migrations, stops);
    }

    /** The transitions of {@code body}, as rules: states of {@code fromMission} to states of {@code toMission}. */
    private static List<Rule> rules(String body, String fromMission, String toMission, String name) {
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
        return rules;
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
