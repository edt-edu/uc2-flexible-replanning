package mbdo.dt.utils;

public final class TypeDetector {

    private TypeDetector() {
    }

    public static Object detectType(String input) {
        if (isBoolean(input)) {
            return Boolean.parseBoolean(input);
        } else if (isInteger(input)) {
            return Long.parseLong(input);
        } else if (isFloat(input)) {
            return Double.parseDouble(input);
        } else {
            return input;
        }
    }

    public static boolean isBoolean(String input) {
        return "true".equalsIgnoreCase(input) || "false".equalsIgnoreCase(input);
    }

    public static boolean isInteger(String input) {
        try {
            Long.parseLong(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isFloat(String input) {
        try {
            Double.parseDouble(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}