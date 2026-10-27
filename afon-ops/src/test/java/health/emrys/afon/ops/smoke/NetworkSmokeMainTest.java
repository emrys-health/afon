package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkSmokeMainTest {

    private static final ExecResult OK     = new ExecResult(0, "HTTP/2 200", "", "curl -I https://up.example");
    private static final ExecResult FAILED = new ExecResult(6, "", "Could not resolve host", "curl -I https://down.example");

    @Test
    void aDomainThatRespondsIsPassedOn() {
        List<ExecResult> results = NetworkSmokeMain.leavingOutDownDomain("up.example", List.of(OK), domain -> {
            throw new AssertionError("no need to resolve it");
        });

        assertThat(results).containsExactly(OK);
    }

    @Test
    void aPodNotGettingAResponseFromADomainThatResolvesHereIsAFailure() {
        // it is the network policy, or the server refusing the pod, that stops it, not the domain being down
        List<ExecResult> results = NetworkSmokeMain.leavingOutDownDomain("down.example", List.of(FAILED), domain -> true);

        assertThat(results).containsExactly(FAILED);
    }

    @Test
    void aDomainThatDoesNotResolveHereEitherIsNotChecked() {
        List<ExecResult> results = NetworkSmokeMain.leavingOutDownDomain("down.example", List.of(FAILED), domain -> false);

        assertThat(results).hasSize(1).allMatch(ExecResult::isSuccess);
        assertThat(results.get(0).stdout()).startsWith("Not checked: down.example");
        assertThat(results.get(0).command()).isEqualTo(FAILED.command());
    }

    @Test
    void onlyTheFailedResultsAreLeftOut() {
        List<ExecResult> results = NetworkSmokeMain.leavingOutDownDomain("down.example", List.of(OK, FAILED), domain -> false);

        assertThat(results).hasSize(2).allMatch(ExecResult::isSuccess);
        assertThat(results.get(0)).isSameAs(OK);
    }
}
