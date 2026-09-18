package mbdo.dt.models;

import mbdo.dt.models.SysMLStructuralModel.ConstraintDefinition;
import mbdo.dt.models.SysMLStructuralModel.ConstraintParameter;
import mbdo.dt.models.SysMLStructuralModel.ConstraintUsage;
import mbdo.dt.utils.SysMLParserUtils;
import mbdo.dt.utils.SysMLParserUtils.Block;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts SysML constraint definitions (the parameterized constraint blocks) and constraint
 * usages (the {@code assert constraint} invocations that bind concrete input values to them).
 * Stateless: every call is given the model text it should read.
 */
@Component
public class ConstraintExtractor {

    public List<ConstraintDefinition> extractConstraintDefinitions(String content) {
        List<ConstraintDefinition> constraintDefinitions = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "constraint def")) {
            constraintDefinitions.add(new ConstraintDefinition(
                    block.name(),
                    extractConstraintParameters(block.body())
            ));
        }

        return constraintDefinitions;
    }

    public List<ConstraintUsage> extractConstraintUsages(String content) {
        List<ConstraintUsage> constraintUsages = new ArrayList<>();

        String identifier = SysMLParserUtils.identifierRegex();
        String qualifiedName = SysMLParserUtils.qualifiedNameRegex();

        Pattern pattern = Pattern.compile("\\bassert\\s+constraint\\s+(" + identifier + ")\\s*:\\s*(" + qualifiedName + ")\\s*\\{", Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String usageName = matcher.group(1).trim();
            String constraintType = matcher.group(2).trim();

            int openBraceIndex = content.indexOf('{', matcher.end() - 1);

            if (openBraceIndex < 0) {
                continue;
            }

            int closeBraceIndex = SysMLParserUtils.findMatchingBrace(content, openBraceIndex);

            if (closeBraceIndex < 0) {
                throw new IllegalArgumentException("Unclosed assert constraint: " + usageName);
            }

            String constraintBody = content.substring(openBraceIndex + 1, closeBraceIndex);

            constraintUsages.add(new ConstraintUsage(
                    usageName,
                    constraintType,
                    extractConstraintInputValues(constraintBody)
            ));
        }

        return constraintUsages;
    }

    private List<ConstraintParameter> extractConstraintParameters(String body) {
        List<ConstraintParameter> parameters = new ArrayList<>();

        String identifier = SysMLParserUtils.identifierRegex();

        Pattern pattern = Pattern.compile("\\b(in|out|inout)\\s+(" + identifier + ")\\s*(?::>|:)\\s*([^;]+);", Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(body);

        while (matcher.find()) {
            String direction = matcher.group(1).trim();
            String name = matcher.group(2).trim();
            String type = matcher.group(3).trim();

            parameters.add(new ConstraintParameter(
                    direction,
                    name,
                    type
            ));
        }

        return parameters;
    }

    private Map<String, String> extractConstraintInputValues(String body) {
        Map<String, String> inputValues = new HashMap<>();

        String identifier = SysMLParserUtils.identifierRegex();

        Pattern pattern = Pattern.compile("\\bin\\s+(" + identifier + ")\\s*=\\s*(.*?);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(body);

        while (matcher.find()) {
            String inputName = matcher.group(1).trim();

            String inputValue = SysMLParserUtils.normalizeWhitespace(
                    matcher.group(2).trim()
            );

            inputValues.put(
                    inputName,
                    SysMLParserUtils.cleanValue(inputValue)
            );
        }

        return inputValues;
    }
}
