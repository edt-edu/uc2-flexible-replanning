package mbdo.dt.utils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Parses a timestamp string into epoch milliseconds, tolerating the real broker's malformed
 * doubly-marked ISO-8601 format (an explicit numeric offset plus a trailing "Z", e.g.
 * "2026-09-14T08:54:38.964168+00:00Z"). Shared by every code path that turns an MQTT
 * "timestamp" JSON field into epoch millis, so a format fix only ever has to be made once.
 */
public final class TimestampParsingUtils {

    private TimestampParsingUtils() {
    }

    public static Long parseEpochMillisOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
        }

        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }

        if (text.endsWith("Z")) {
            try {
                return OffsetDateTime.parse(text.substring(0, text.length() - 1))
                        .toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }

        return null;
    }
}
