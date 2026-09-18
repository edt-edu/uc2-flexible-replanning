package mbdo.dt.utils;

import mbdo.dt.models.SysMLBehavioralModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SysMLParserUtils {

    private SysMLParserUtils() {
    }

    public static String identifierRegex() {
        return "[A-Za-z_][A-Za-z0-9_]*";
    }

    public static String qualifiedNameRegex() {
        String identifier = identifierRegex();
        return identifier + "(?:::" + identifier + ")*";
    }

    public static String keywordToRegex(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }

        String[] words = keyword.trim().split("\\s+");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append("\\s+");
            }

            result.append(Pattern.quote(words[i]));
        }

        return result.toString();
    }

    public static String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace("\n", " ")
                .replace("\r", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String cleanValue(String value) {
        if (value == null) {
            return null;
        }

        String result = value.trim();

        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            return result.substring(1, result.length() - 1);
        }

        return result;
    }

    public static String cleanContent(String content) {
        if (content == null) {
            return "";
        }

        return content
                .replace("\r\n", "\n")
                .replaceAll("(?s)doc\\s*/\\*.*?\\*/", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    public static int findMatchingBrace(String content, int openBraceIndex) {
        if (content == null || openBraceIndex < 0 || openBraceIndex >= content.length()) {
            return -1;
        }

        int depth = 0;
        boolean insideString = false;
        char previous = '\0';

        for (int i = openBraceIndex; i < content.length(); i++) {
            char current = content.charAt(i);

            if (current == '"' && previous != '\\') {
                insideString = !insideString;
            }

            if (!insideString) {
                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;

                    if (depth == 0) {
                        return i;
                    }
                }
            }

            previous = current;
        }

        return -1;
    }

    public static Long parseDurationMillis(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        Pattern pattern = Pattern.compile(
                "(\\d+)\\s*(?:\\[\\s*(ms|s|min|h)\\s*\\]|(ms|s|min|h))?",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(expression.trim());

        if (!matcher.find()) {
            return null;
        }

        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);

        if (unit == null || unit.isBlank()) {
            unit = "s";
        }

        return switch (unit.toLowerCase()) {
            case "ms" -> value;
            case "s" -> value * 1000L;
            case "min" -> value * 60_000L;
            case "h" -> value * 3_600_000L;
            default -> value * 1000L;
        };
    }

    public static SysMLBehavioralModel.Operator toOperator(String symbol) {
        return switch (symbol) {
            case "=", "==" -> SysMLBehavioralModel.Operator.EQUALS;
            case "!=" -> SysMLBehavioralModel.Operator.NOT_EQUALS;
            case ">" -> SysMLBehavioralModel.Operator.GREATER_THAN;
            case ">=" -> SysMLBehavioralModel.Operator.GREATER_THAN_OR_EQUALS;
            case "<" -> SysMLBehavioralModel.Operator.LESS_THAN;
            case "<=" -> SysMLBehavioralModel.Operator.LESS_THAN_OR_EQUALS;
            default -> SysMLBehavioralModel.Operator.UNKNOWN;
        };
    }

    public static SysMLBehavioralModel.Condition parseCondition(String clause) {
        if (clause == null || clause.isBlank()) {
            return new SysMLBehavioralModel.Condition(
                    clause,
                    SysMLBehavioralModel.Operator.UNKNOWN,
                    null,
                    clause
            );
        }

        for (String symbol : List.of("==", "!=", ">=", "<=", ">", "<", "=")) {
            int operatorIndex = clause.indexOf(symbol);

            if (operatorIndex < 0) {
                continue;
            }

            String leftOperand = clause.substring(0, operatorIndex).trim();
            String rightOperand = cleanValue(clause.substring(operatorIndex + symbol.length()).trim());

            if (leftOperand.isBlank() || rightOperand.isBlank()) {
                break;
            }

            return new SysMLBehavioralModel.Condition(
                    leftOperand,
                    toOperator(symbol),
                    rightOperand,
                    clause
            );
        }

        return new SysMLBehavioralModel.Condition(
                clause,
                SysMLBehavioralModel.Operator.UNKNOWN,
                null,
                clause
        );
    }

    public static boolean startsWithIgnoreCase(String value, String prefix) {
        if (value == null || prefix == null) {
            return false;
        }

        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static int indexOfStandaloneIf(String value) {
        if (value == null) {
            return -1;
        }

        Pattern pattern = Pattern.compile("\\s+if\\s+", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(value);

        if (matcher.find()) {
            return matcher.start();
        }

        return -1;
    }

    public static String removeDoActionBlock(String guardExpression) {
        if (guardExpression == null) {
            return "";
        }

        Pattern pattern = Pattern.compile(
                "\\s+do\\s+action\\s+.*$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(guardExpression);

        if (matcher.find()) {
            return guardExpression.substring(0, matcher.start()).trim();
        }

        return guardExpression.trim();
    }

    public static List<String> splitTopLevelAnd(String expression) {
        List<String> clauses = new ArrayList<>();

        if (expression == null || expression.isBlank()) {
            return clauses;
        }

        String text = expression.trim();
        int start = 0;
        int depth = 0;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);

            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            }

            if (depth == 0 && i + 5 <= text.length()
                    && text.substring(i, i + 5).equalsIgnoreCase(" and ")) {
                String clause = text.substring(start, i).trim();

                if (!clause.isBlank()) {
                    clauses.add(clause);
                }

                i = i + 4;
                start = i + 1;
            }
        }

        String lastClause = text.substring(start).trim();

        if (!lastClause.isBlank()) {
            clauses.add(lastClause);
        }

        return clauses;
    }

    public static boolean isFunctionCall(String expression) {
        if (expression == null) {
            return false;
        }

        String text = expression.trim();
        int openParenthesis = text.indexOf('(');

        return openParenthesis > 0 && text.endsWith(")");
    }

    public static String functionName(String expression) {
        if (!isFunctionCall(expression)) {
            return null;
        }

        return expression.substring(0, expression.indexOf('(')).trim();
    }

    public static List<String> functionArguments(String expression) {
        List<String> arguments = new ArrayList<>();

        if (!isFunctionCall(expression)) {
            return arguments;
        }

        String text = expression.trim();
        String inside = text.substring(text.indexOf('(') + 1, text.length() - 1);

        int start = 0;
        int depth = 0;

        for (int i = 0; i < inside.length(); i++) {
            char current = inside.charAt(i);

            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                String argument = inside.substring(start, i).trim();

                if (!argument.isBlank()) {
                    arguments.add(argument);
                }

                start = i + 1;
            }
        }

        String lastArgument = inside.substring(start).trim();

        if (!lastArgument.isBlank()) {
            arguments.add(lastArgument);
        }

        return arguments;
    }

    public static List<Block> extractBlocks(String content, String keyword) {
        List<Block> blocks = new ArrayList<>();

        String identifier = identifierRegex();
        String keywordRegex = keywordToRegex(keyword);
        Pattern pattern = Pattern.compile(
                "\\b" + keywordRegex + "\\s+(" + identifier + ")\\s*\\{"
        );

        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String blockName = matcher.group(1);

            int blockStartIndex = matcher.start();
            int openBraceIndex = content.indexOf('{', matcher.end() - 1);

            if (openBraceIndex < 0) {
                continue;
            }

            int closeBraceIndex = findMatchingBrace(content, openBraceIndex);

            if (closeBraceIndex < 0) {
                throw new IllegalArgumentException("Unclosed block: " + keyword + " " + blockName);
            }

            blocks.add(new Block(
                    blockName,
                    blockStartIndex,
                    openBraceIndex,
                    closeBraceIndex,
                    content.substring(openBraceIndex + 1, closeBraceIndex)
            ));
        }

        return blocks;
    }

    public static String removeNestedDefinitionBlocks(String body) {
        String result = body;

        for (String keyword : List.of(
                "package",
                "part def",
                "action def",
                "attribute def",
                "enum def",
                "state def",
                "constraint def"
        )) {
            result = removeNestedBlocks(result, keyword);
        }

        return result;
    }

    public static String removeNestedBlocks(String body, String keyword) {
        List<Block> blocks = extractBlocks(body, keyword);
        String result = body;

        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);

            result = result.substring(0, block.startIndex())
                    + result.substring(block.closeBraceIndex() + 1);
        }

        return result;
    }

    public record Block(
            String name,
            int startIndex,
            int openBraceIndex,
            int closeBraceIndex,
            String body
    ) {
    }

}