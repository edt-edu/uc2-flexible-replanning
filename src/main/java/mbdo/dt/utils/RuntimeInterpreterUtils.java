package mbdo.dt.utils;

import mbdo.dt.models.SysMLBehavioralModel;

import java.util.Map;
import java.util.Objects;

public final class RuntimeInterpreterUtils {

    private RuntimeInterpreterUtils() {
    }

    public static String normalizeValue(Object value) {
        if (value == null) {
            return "";
        }

        String normalized = String.valueOf(value).trim();

        normalized = normalized.replace("\"", "");
        normalized = normalized.replace("'", "");
        normalized = normalized.replace(";", "");

        int enumSeparatorIndex = normalized.lastIndexOf("::");

        if (enumSeparatorIndex >= 0) {
            normalized = normalized.substring(enumSeparatorIndex + 2);
        }

        return normalized.trim();
    }

    public static boolean valuesMatch(Object expectedValue, Object observedValue) {
        String normalizedExpectedValue = normalizeValue(expectedValue);
        String normalizedObservedValue = normalizeValue(observedValue);

        return normalizedExpectedValue.equalsIgnoreCase(normalizedObservedValue);
    }

    public static boolean isRelativeTimeTransition(SysMLBehavioralModel.Transition transition) {
        return transition != null
                && transition.triggerKind() == SysMLBehavioralModel.TriggerKind.RELATIVE_TIME;
    }

    public static Long parseDurationMillisOrNull(String durationExpression) {
        if (durationExpression == null || durationExpression.isBlank()) {
            return null;
        }

        try {
            return SysMLParserUtils.parseDurationMillis(durationExpression);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static SysMLBehavioralModel.Condition parseRuntimeCondition(String clause) {
        return SysMLParserUtils.parseCondition(clause);
    }

    public static Object resolveRuntimeArithmetic(String expression, Map<String, ?> values) {
        if (expression == null) {
            return null;
        }

        String text = expression.trim();

        int plusIndex = text.indexOf('+');

        if (plusIndex > 0) {
            Double left = toDouble(resolveRuntimeArithmetic(text.substring(0, plusIndex), values));
            Double right = toDouble(resolveRuntimeArithmetic(text.substring(plusIndex + 1), values));

            if (left == null || right == null) {
                return null;
            }

            return left + right;
        }

        int minusIndex = text.indexOf('-');

        if (minusIndex > 0) {
            Double left = toDouble(resolveRuntimeArithmetic(text.substring(0, minusIndex), values));
            Double right = toDouble(resolveRuntimeArithmetic(text.substring(minusIndex + 1), values));

            if (left == null || right == null) {
                return null;
            }

            return left - right;
        }

        return resolveRuntimeValue(text, values);
    }

    public static Object resolveRuntimeValue(String operand, Map<String, ?> values) {
        if (operand == null) {
            return null;
        }

        String value = operand.trim();

        if (values != null && values.containsKey(value)) {
            return values.get(value);
        }

        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }

        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }

        if (value.contains("::")) {
            return value.substring(value.lastIndexOf("::") + 2);
        }

        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }

            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UnobservedAttributeException(value);
        }
    }

    public static boolean evaluateRuntimeComparison(
            Object leftValue,
            SysMLBehavioralModel.Operator operator,
            Object rightValue
    ) {
        Double leftNumber = toDouble(leftValue);
        Double rightNumber = toDouble(rightValue);

        return switch (operator) {
            case EQUALS -> valuesRuntimeEqual(leftValue, rightValue);
            case NOT_EQUALS -> !valuesRuntimeEqual(leftValue, rightValue);
            case GREATER_THAN -> leftNumber != null && rightNumber != null && leftNumber > rightNumber;
            case GREATER_THAN_OR_EQUALS -> leftNumber != null && rightNumber != null && leftNumber >= rightNumber;
            case LESS_THAN -> leftNumber != null && rightNumber != null && leftNumber < rightNumber;
            case LESS_THAN_OR_EQUALS -> leftNumber != null && rightNumber != null && leftNumber <= rightNumber;
            case UNKNOWN -> false;
        };
    }

    public static boolean valuesRuntimeEqual(Object leftValue, Object rightValue) {
        if (leftValue == null || rightValue == null) {
            return Objects.equals(leftValue, rightValue);
        }

        Double leftNumber = toDouble(leftValue);
        Double rightNumber = toDouble(rightValue);

        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber) == 0;
        }

        if (leftValue instanceof Boolean || rightValue instanceof Boolean) {
            return Boolean.parseBoolean(String.valueOf(leftValue))
                    == Boolean.parseBoolean(String.valueOf(rightValue));
        }

        String normalizedLeftValue = normalizeValue(leftValue);
        String normalizedRightValue = normalizeValue(rightValue);

        return normalizedLeftValue.equalsIgnoreCase(normalizedRightValue);
    }

    public static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}