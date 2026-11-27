package health.emrys.afon.security.keycloak;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time password of an authenticator: the codes of RFC 6238, and a code that is not given twice.
 */
class OneTimePasswordTest {

    /** The secret of the test vectors of RFC 6238 for HMAC-SHA1. */
    private static final String RFC_SECRET = "12345678901234567890";

    /** A clock that tells what it is told, and moves when it is told to. */
    private static final class MovingClock extends Clock {
        final AtomicLong millis;

        MovingClock(long seconds) {
            millis = new AtomicLong(seconds * 1000);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    void makesTheCodesOfTheTestVectorsOfRfc6238() {
        // the last six of the eight digits that the RFC gives, at the times 59, 1111111109, 1111111111, 1234567890, 2000000000 and 20000000000
        assertThat(OneTimePassword.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(OneTimePassword.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(OneTimePassword.code(RFC_SECRET, 1111111111L / 30)).isEqualTo("050471");
        assertThat(OneTimePassword.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(OneTimePassword.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
        assertThat(OneTimePassword.code(RFC_SECRET, 20000000000L / 30)).isEqualTo("353130");
    }

    @Test
    void givesTheCodeOfTheCurrentStepThenTheOtherStepsKeycloakTakesAndNeverTheSameTwice() throws Exception {
        MovingClock clock = new MovingClock(1_000_000_000L);
        long now = 1_000_000_000L / 30;
        OneTimePassword passwords = new OneTimePassword(clock, millis -> {
            throw new AssertionError("it should not wait yet");
        });

        assertThat(passwords.next(RFC_SECRET)).isEqualTo(OneTimePassword.code(RFC_SECRET, now));
        assertThat(passwords.next(RFC_SECRET)).isEqualTo(OneTimePassword.code(RFC_SECRET, now + 1));
        assertThat(passwords.next(RFC_SECRET)).isEqualTo(OneTimePassword.code(RFC_SECRET, now - 1));
    }

    @Test
    void waitsForTheNextStepWhenItHasGivenEveryCodeKeycloakTakes() throws Exception {
        MovingClock clock = new MovingClock(1_000_000_000L);
        List<Long> waits = new ArrayList<>();
        OneTimePassword passwords = new OneTimePassword(clock, millis -> {
            waits.add(millis);
            clock.millis.addAndGet(millis);
        });
        for (int i = 0; i < 3; i++) {
            passwords.next(RFC_SECRET);
        }

        String fourth = passwords.next(RFC_SECRET);

        assertThat(waits).hasSize(1);
        long later = clock.millis.get() / 1000 / 30;
        assertThat(fourth).isIn(OneTimePassword.code(RFC_SECRET, later), OneTimePassword.code(RFC_SECRET, later + 1), OneTimePassword.code(RFC_SECRET, later - 1));
    }

    @Test
    void keepsTheCodesOfTwoSecretsApart() throws Exception {
        OneTimePassword passwords = new OneTimePassword(new MovingClock(1_000_000_000L), millis -> {
            throw new AssertionError("it should not wait");
        });
        passwords.next("first-secret");
        passwords.next("first-secret");
        passwords.next("first-secret");

        assertThat(passwords.next("second-secret")).isEqualTo(OneTimePassword.code("second-secret", 1_000_000_000L / 30));
    }

    @Test
    void afterTheNextStepWaitsForItToBeginAndGivesTheCodeOfTheOneThatFollowsIt() throws Exception {
        // 10 seconds into a step
        MovingClock clock = new MovingClock(1_000_000_000L - (1_000_000_000L % 30) + 10);
        List<Long> waits = new ArrayList<>();
        OneTimePassword passwords = new OneTimePassword(clock, millis -> {
            waits.add(millis);
            clock.millis.addAndGet(millis);
        });
        long step = clock.millis.get() / 1000 / 30;

        String code = passwords.afterTheNextStep(RFC_SECRET);

        // 20 seconds to the next step, and a second past it
        assertThat(waits).containsExactly(21_000L);
        assertThat(code).isEqualTo(OneTimePassword.code(RFC_SECRET, step + 2));
    }
}
