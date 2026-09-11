package health.emrys.afon.ops.steps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static health.emrys.afon.ops.steps.DurationUtils.formatDuration;
import static health.emrys.afon.ops.steps.DurationUtils.parseDuration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0s",
            "5000, 5s",
            "90000, 1m30s",
            "60000, 1m",
            "1200000, 20m",
            "3600000, 1h",
            "7200000, 2h",
            "3661000, 1h1m1s",
    })
    public void testFormatDuration(long millis, String expected) {
        assertEquals(expected, formatDuration(millis));
    }

    @ParameterizedTest
    @CsvSource({
            "0s, PT0S",
            "30s, PT30S",
            "20m, PT20M",
            "1h, PT1H",
            "1h30m, PT1H30M",
            "1m30s, PT1M30S",
            "1h30m15s, PT1H30M15S",
    })
    public void testParseDuration(String value, String expectedIso) {
        assertEquals(Duration.parse(expectedIso), parseDuration(value));
    }

    @Test
    public void testParseDurationTrimsWhitespace() {
        assertEquals(Duration.ofMinutes(20), parseDuration("  20m  "));
    }

    @Test
    public void testParseDurationRejectsUnsupportedUnit() {
        assertThrows(IllegalArgumentException.class, () -> parseDuration("20x"));
    }

    @Test
    public void testParseDurationRejectsNonNumericAmount() {
        assertThrows(IllegalArgumentException.class, () -> parseDuration("xs"));
    }

    @Test
    public void testParseDurationRejectsOutOfOrderComponents() {
        assertThrows(IllegalArgumentException.class, () -> parseDuration("30s1m"));
    }

    @Test
    public void testParseDurationRejectsEmptyString() {
        assertThrows(IllegalArgumentException.class, () -> parseDuration(""));
    }
}
