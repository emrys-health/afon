package health.emrys.afon.security.keycloak;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * The one-time password of an authenticator, as KeyCloak's OTP form takes it: time-based (RFC 6238), HMAC-SHA1, six digits, a step of thirty seconds.  The key
 * is the bytes of the secret that KeyCloak shows (and that an authenticator app is given, in base32), which is what its form carries as {@code totpSecret}.
 * <p>
 * KeyCloak takes the code of the step before and after the current one too, and refuses a code that it has taken, so {@link #next} gives each secret a code of
 * a step that it did not give already, and waits for the next step when it has given those that KeyCloak takes.
 */
public final class OneTimePassword {

    /** The seconds that a code holds. */
    public static final long STEP_SECONDS = 30;

    private final Clock clock;
    private final Sleeper sleeper;
    private final Set<String> given = new HashSet<>();

    /** Waits, which a test replaces. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public OneTimePassword() {
        this(Clock.systemUTC(), Thread::sleep);
    }

    OneTimePassword(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * @param secret the secret of the authenticator, as KeyCloak shows it
     * @param step   the step, the seconds since the epoch divided by {@link #STEP_SECONDS}
     * @return the six digits of the step
     */
    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No HMAC-SHA1 to make a one-time password with", e);
        }
    }

    /**
     * Waits for the next step and gives the code of the one after it, for when the codes of the current and the next step have been taken, by this or by another process that
     * signs in as the same member: KeyCloak takes a code once, and refuses one of a step that is not after the last that it took, so the first it can take is of the step
     * that follows the next, which it takes only once its own clock has reached the next.
     *
     * @return the code of the secret for the step after the one that comes, once that has begun
     */
    public synchronized String afterTheNextStep(String secret) throws InterruptedException {
        long now = clock.millis();
        long boundary = (now / 1000 / STEP_SECONDS + 1) * STEP_SECONDS * 1000;
        // a second past the boundary, for the clocks that are not quite the same
        sleeper.sleep(boundary - now + 1000);
        long step = clock.millis() / 1000 / STEP_SECONDS + 1;
        given.add(secret + ":" + step);
        return code(secret, step);
    }

    /**
     * @return the code of the secret for the current step, or the one before or after it, that this has not given before: the current step's first, and
     * after waiting for the next one when all three were given
     */
    public synchronized String next(String secret) throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long now = clock.millis() / 1000 / STEP_SECONDS;
            for (long step : new long[]{now, now + 1, now - 1}) {
                if (given.add(secret + ":" + step)) {
                    return code(secret, step);
                }
            }
            sleeper.sleep((STEP_SECONDS + 1) * 1000);
        }
        throw new IllegalStateException("No one-time password of a step that was not used already");
    }
}
