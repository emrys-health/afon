package health.emrys.afon.ops.kube.commands;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A check that expects a response is retried for a while, as a pod that has just started may fail its first DNS lookups.
 */
class CurlCommandRetryTest {

    private static final Duration WINDOW = Duration.ofSeconds(30);
    private static final Duration PAUSE  = Duration.ofSeconds(3);

    private static ExecResult result(int exitCode) {
        return new ExecResult(exitCode, "", "", "curl", "pod");
    }

    @Test
    void aSuccessAtTheFirstAttemptIsNotRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long>    sleeps   = new ArrayList<>();

        ExecResult r = CurlCommand.retryingUntilSuccess(() -> { attempts.incrementAndGet(); return result(0); },
                                                        WINDOW, PAUSE, sleeps::add);

        assertThat(r.isSuccess()).isTrue();
        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aFailureFollowedByASuccessPasses() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        ExecResult r = CurlCommand.retryingUntilSuccess(() -> result(attempts.incrementAndGet() < 3 ? 6 : 0),
                                                        WINDOW, PAUSE, ms -> {});

        assertThat(r.isSuccess()).isTrue();
        assertThat(attempts).hasValue(3);
    }

    @Test
    void aCheckThatNeverSucceedsFailsWithTheLastResultAfterTheWindow() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long>    sleeps   = new ArrayList<>();

        ExecResult r = CurlCommand.retryingUntilSuccess(() -> result(attempts.incrementAndGet()),
                                                        WINDOW, PAUSE, sleeps::add);

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.exitCode()).isEqualTo(attempts.get());   // the last one
        assertThat(attempts).hasValue(11);                    // 1 + 30s / 3s
        assertThat(sleeps).hasSize(10).allMatch(ms -> ms == 3000);
    }
}
