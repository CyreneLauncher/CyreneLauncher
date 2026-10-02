package runner.lwjgl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed access to {@link JsonReader} values; type errors are unsupported metadata. */
final class JsonFields {
    private JsonFields() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String where,
            LaunchPlanException.Category category) throws LaunchPlanException {
        if (!(value instanceof Map)) throw new LaunchPlanException(category, where + " must be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, String where,
            LaunchPlanException.Category category) throws LaunchPlanException {
        if (!(value instanceof List)) throw new LaunchPlanException(category, where + " must be an array");
        return (List<Object>) value;
    }

    static String string(Object value, String where,
            LaunchPlanException.Category category) throws LaunchPlanException {
        if (!(value instanceof String) || ((String) value).isEmpty())
            throw new LaunchPlanException(category, where + " must be a non-empty string");
        return (String) value;
    }

    static long nonNegativeLong(Object value, String where,
            LaunchPlanException.Category category) throws LaunchPlanException {
        if (value instanceof BigDecimal) {
            try {
                long result = ((BigDecimal) value).longValueExact();
                if (result >= 0) return result;
            } catch (ArithmeticException ignored) {
                // Reported below.
            }
        }
        throw new LaunchPlanException(category, where + " must be a non-negative integer");
    }

    static void onlyKeys(Map<String, Object> object, Set<String> allowed, String where,
            LaunchPlanException.Category category) throws LaunchPlanException {
        for (String key : object.keySet())
            if (!allowed.contains(key))
                throw new LaunchPlanException(category, where + " has unsupported field \"" + key + "\"");
    }

    static boolean lowercaseHex(String value, int length) {
        if (value.length() != length) return false;
        for (int i = 0; i < length; ++i) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
