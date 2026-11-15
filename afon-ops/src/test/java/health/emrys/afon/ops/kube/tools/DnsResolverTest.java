package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wait for the cluster to reach its domains is bounded, needs several answers in a row, and reports what it did not reach.
 */
class DnsResolverTest {

    private static final Duration PAUSE   = Duration.ofSeconds(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static ExecResult result(boolean reached) {
        return new ExecResult(reached ? 0 : 6, "", reached ? "" : "Could not resolve host", "curl", "pod");
    }

    @Test
    void aDomainMustBeReachedSeveralTimesInARow() {
        Map<String, Integer> probes = new HashMap<>();
        List<Long>           sleeps = new ArrayList<>();

        var unreached = DnsResolver.waitUntilReachable(List.of("a", "b"), domain -> {
            probes.merge(domain, 1, Integer::sum);
            return result(true);
        }, TIMEOUT, PAUSE, 3, sleeps::add);

        assertThat(unreached).isEmpty();
        assertThat(probes).containsEntry("a", 3).containsEntry("b", 3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void aFailureStartsTheCountAgain() {
        Map<String, Integer> probes = new HashMap<>();

        // one replica of the DNS answers, then the next does not
        var unreached = DnsResolver.waitUntilReachable(List.of("flaky"), domain -> result(probes.merge(domain, 1, Integer::sum) != 2),
                                                       TIMEOUT, PAUSE, 3, millis -> {
            });

        assertThat(unreached).isEmpty();
        assertThat(probes.get("flaky")).as("1 pass, 1 failure, then 3 in a row").isEqualTo(5);
    }

    @Test
    void givesUpAtTheTimeoutNamingTheDomainsAndWhy() {
        List<Long> sleeps = new ArrayList<>();

        var unreached = DnsResolver.waitUntilReachable(List.of("ok", "down"), domain -> result(domain.equals("ok")), TIMEOUT, PAUSE, 2,
                                                       sleeps::add);

        assertThat(unreached).containsOnlyKeys("down");
        assertThat(unreached.get("down").stderr()).contains("Could not resolve host");
        assertThat(sleeps).hasSize(6).allMatch(ms -> ms == 10_000L);
    }

    @Test
    void aDomainThatOnlySometimesAnswersIsReportedAsSuch() {
        Map<String, Integer> probes = new HashMap<>();

        var unreached = DnsResolver.waitUntilReachable(List.of("odd"), domain -> result(probes.merge(domain, 1, Integer::sum) % 2 == 1),
                                                       TIMEOUT, PAUSE, 2, millis -> {
            });

        assertThat(unreached).containsOnlyKeys("odd");
        assertThat(unreached.get("odd").stderr()).contains("not 2 times in a row");
    }
}
