package mbdo.dt.models;

import mbdo.dt.models.SysMLStructuralModel.ActionDefinition;
import mbdo.dt.models.SysMLStructuralModel.ActionParameter;
import mbdo.dt.models.SysMLStructuralModel.Attribute;
import mbdo.dt.models.SysMLStructuralModel.AttributeDefinition;
import mbdo.dt.models.SysMLStructuralModel.CalcDefinition;
import mbdo.dt.models.SysMLStructuralModel.CalcParameter;
import mbdo.dt.models.SysMLStructuralModel.EnumDefinition;
import mbdo.dt.models.SysMLStructuralModel.PartDefinition;
import mbdo.dt.models.SysMLStructuralModel.PerformedAction;
import mbdo.dt.models.SysMLStructuralModel.StateUsage;
import mbdo.dt.utils.SysMLParserUtils;
import mbdo.dt.utils.SysMLParserUtils.Block;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the definition-level structural elements of a SysML model - the root package name,
 * enums, attribute/action/calc/part definitions and everything nested inside them (parameters,
 * attributes, performed actions, state usages). Stateless: every call is given the model text
 * it should read.
 */
@Component
public class StructuralDefinitionExtractor {

    public String extractRootPackageName(String content) {
        String identifier = SysMLParserUtils.identifierRegex();

        Pattern pattern = Pattern.compile("\\bpackage\\s+(" + identifier + ")\\s*\\{");

        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return "UNKNOWN_PACKAGE";
    }

    public List<EnumDefinition> extractEnumDefinitions(String content) {
        List<EnumDefinition> enumDefinitions = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "enum def")) {
            List<String> literals = new ArrayList<>();

            for (String token : block.body().split(";")) {
                String literal = token.trim();

                if (!literal.isBlank()) {
                    literals.add(literal);
                }
            }

            enumDefinitions.add(new EnumDefinition(block.name(), literals));
        }

        return enumDefinitions;
    }

    public List<AttributeDefinition> extractAttributeDefinitions(String content) {
        List<AttributeDefinition> attributeDefinitions = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "attribute def")) {
            String attributeDefinitionBody = SysMLParserUtils.removeNestedDefinitionBlocks(block.body());

            attributeDefinitions.add(new AttributeDefinition(
                    block.name(),
                    extractAttributes(attributeDefinitionBody)
            ));
        }

        return attributeDefinitions;
    }

    public List<ActionDefinition> extractActionDefinitions(String content) {
        List<ActionDefinition> actionDefinitions = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "action def")) {
            actionDefinitions.add(new ActionDefinition(
                    block.name(),
                    extractActionParameters(block.body()),
                    extractActionBodySteps(block.body())
            ));
        }

        return actionDefinitions;
    }

    public List<CalcDefinition> extractCalcDefinitions(String content) {
        List<CalcDefinition> calcDefinitions = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "calc def")) {
            calcDefinitions.add(new CalcDefinition(
                    block.name(),
                    extractCalcParameters(block.body()),
                    extractCalcReturnType(block.body()),
                    extractCalcReturnExpression(block.body())
            ));
        }

        return calcDefinitions;
    }

    public List<PartDefinition> extractPartDefinitions(String content) {
        List<PartDefinition> partDefinitions = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "part def")) {
            String partBody = SysMLParserUtils.removeNestedDefinitionBlocks(block.body());
            partDefinitions.add(new PartDefinition(
                    block.name(),
                    extractAttributes(partBody),
                    extractPerformedActions(partBody),
                    extractStateUsages(partBody)
            ));
        }

        return partDefinitions;
    }

    private List<CalcParameter> extractCalcParameters(String body) {
        List<CalcParameter> parameters = new ArrayList<>();

        String identifier = SysMLParserUtils.identifierRegex();

        Pattern pattern = Pattern.compile(
                "\\b(in|out|inout)\\s+(" + identifier + ")\\s*(?::>|:)\\s*([^;]+);",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(body);

        while (matcher.find()) {
            parameters.add(new CalcParameter(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(3).trim()
            ));
        }

        return parameters;
    }

    private String extractCalcReturnType(String body) {
        Pattern pattern = Pattern.compile("\\breturn\\s*:\\s*([^=;]+)(?:=|;)",  Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher matcher = pattern.matcher(body);

        if (matcher.find()) {
            return SysMLParserUtils.normalizeWhitespace(matcher.group(1));
        }

        return null;
    }

    private String extractCalcReturnExpression(String body) {
        Pattern pattern = Pattern.compile("\\breturn\\s*:\\s*[^=;]+\\s*=\\s*(.*?);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher matcher = pattern.matcher(body);

        if (matcher.find()) {
            return SysMLParserUtils.normalizeWhitespace(matcher.group(1));
        }

        return null;
    }

    private List<String> extractActionBodySteps(String body) {
        List<String> bodySteps = new ArrayList<>();

        for (String rawStep : body.split(";")) {
            String step = SysMLParserUtils.normalizeWhitespace(rawStep);

            if (step.isBlank()) {
                continue;
            }

            if (step.startsWith("in ")
                    || step.startsWith("out ")
                    || step.startsWith("inout ")) {
                continue;
            }

            bodySteps.add(step);
        }

        return bodySteps;
    }

    private List<Attribute> extractAttributes(String body) {
        List<Attribute> attributes = new ArrayList<>();

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();

            if (!line.startsWith("attribute ")) {
                continue;
            }

            Attribute attribute = extractAttribute(line);

            if (attribute != null) {
                attributes.add(attribute);
            }
        }

        return attributes;
    }

    private Attribute extractAttribute(String line) {
        String declaration = line.substring("attribute ".length()).trim();
        NameTypeDefault parsed = parseNameTypeDefault(declaration);

        return parsed == null ? null : new Attribute(parsed.name(), parsed.type(), parsed.defaultValue());
    }

    private List<ActionParameter> extractActionParameters(String body) {
        List<ActionParameter> parameters = new ArrayList<>();

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();

            if (line.startsWith("in ")
                    || line.startsWith("out ")
                    || line.startsWith("inout ")) {

                ActionParameter parameter = extractActionParameter(line);

                if (parameter != null) {
                    parameters.add(parameter);
                }
            }
        }

        return parameters;
    }

    private ActionParameter extractActionParameter(String line) {
        String direction;
        String declaration;

        if (line.startsWith("inout ")) {
            direction = "inout";
            declaration = line.substring("inout ".length()).trim();
        } else if (line.startsWith("out ")) {
            direction = "out";
            declaration = line.substring("out ".length()).trim();
        } else if (line.startsWith("in ")) {
            direction = "in";
            declaration = line.substring("in ".length()).trim();
        } else {
            return null;
        }

        if (declaration.startsWith("attribute ")) {
            declaration = declaration.substring("attribute ".length()).trim();
        }

        NameTypeDefault parsed = parseNameTypeDefault(declaration);

        return parsed == null ? null : new ActionParameter(direction, parsed.name(), parsed.type(), parsed.defaultValue());
    }

    private NameTypeDefault parseNameTypeDefault(String declaration) {
        int colonIndex = declaration.indexOf(':');

        if (colonIndex < 0) {
            return null;
        }

        String name = declaration.substring(0, colonIndex).trim();
        String typeAndDefaultValue = declaration.substring(colonIndex + 1)
                .replace(";", "")
                .trim();

        String type = typeAndDefaultValue;
        String defaultValue = null;

        int equalsIndex = typeAndDefaultValue.indexOf('=');

        if (equalsIndex >= 0) {
            type = typeAndDefaultValue.substring(0, equalsIndex).trim();
            defaultValue = typeAndDefaultValue.substring(equalsIndex + 1).trim();
        }

        if (name.isBlank() || type.isBlank()) {
            return null;
        }

        return new NameTypeDefault(name, type, defaultValue);
    }

    private record NameTypeDefault(String name, String type, String defaultValue) {
    }

    private List<PerformedAction> extractPerformedActions(String body) {
        List<PerformedAction> performedActions = new ArrayList<>();

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();

            if (!line.startsWith("perform action ")) {
                continue;
            }

            String declaration = line.substring("perform action ".length())
                    .replace(";", "")
                    .trim();

            int colonIndex = declaration.indexOf(':');

            if (colonIndex < 0) {
                continue;
            }

            String name = declaration.substring(0, colonIndex).trim();
            String type = declaration.substring(colonIndex + 1).trim();

            if (!name.isBlank() && !type.isBlank()) {
                performedActions.add(new PerformedAction(name, type));
            }
        }

        return performedActions;
    }

    private List<StateUsage> extractStateUsages(String body) {
        List<StateUsage> stateUsages = new ArrayList<>();

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();

            if (!line.startsWith("state ") || line.startsWith("state def ")) {
                continue;
            }

            if (!line.contains(":")) {
                continue;
            }

            String declaration = line.substring("state ".length())
                    .replace(";", "")
                    .replace("{}", "")
                    .trim();

            int colonIndex = declaration.indexOf(':');

            if (colonIndex < 0) {
                continue;
            }

            String name = declaration.substring(0, colonIndex).trim();
            String type = declaration.substring(colonIndex + 1).trim();

            if (!name.isBlank() && !type.isBlank()) {
                stateUsages.add(new StateUsage(name, type));
            }
        }

        return stateUsages;
    }
}
