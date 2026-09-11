package health.emrys.afon.ops.steps;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DurationUtils {
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");

    public static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long         hours    = duration.toHours();
        int          minutes  = duration.toMinutesPart();
        int          seconds  = duration.toSecondsPart();
        StringBuilder result  = new StringBuilder();
        if (hours > 0) {
            result.append(hours).append("h");
        }
        if (minutes > 0) {
            result.append(minutes).append("m");
        }
        if (seconds > 0 || result.isEmpty()) {
            result.append(seconds).append("s");
        }
        return result.toString();
    }

    /**
     * Parses durations such as {@code "30s"}, {@code "20m"}, {@code "1h"}, or a
     * combination such as {@code "1h30m"} or {@code "1m30s"}. Components must be
     * given in descending order (h, then m, then s) and each at most once.
     */
    public static Duration parseDuration(String value) {
        String  trimmed = value.trim();
        Matcher matcher = DURATION_PATTERN.matcher(trimmed);
        if (!matcher.matches() || trimmed.isEmpty()) {
            throw new IllegalArgumentException("Unsupported duration format: " + value);
        }
        String hours   = matcher.group(1);
        String minutes = matcher.group(2);
        String seconds = matcher.group(3);
        if (hours == null && minutes == null && seconds == null) {
            throw new IllegalArgumentException("Unsupported duration format: " + value);
        }
        Duration duration = Duration.ZERO;
        if (hours != null) {
            duration = duration.plusHours(Long.parseLong(hours));
        }
        if (minutes != null) {
            duration = duration.plusMinutes(Long.parseLong(minutes));
        }
        if (seconds != null) {
            duration = duration.plusSeconds(Long.parseLong(seconds));
        }
        return duration;
    }
}
