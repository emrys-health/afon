package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkSmokeMainTest {

    private static final ExecResult OK     = new ExecResult(0, "HTTP/2 200", "", "curl -I https://up.example", "host");
    private static final ExecResult FAILED = new ExecResult(6, "", "Could not resolve host", "curl -I https://down.example", "host");

    private static ExecResult connectedAfter(String seconds) {
        return new ExecResult(28, seconds, "", "curl -w %{time_connect} telnet://10.0.0.1:5901", "polaris-pod");
    }

    @Test
    void aNotebookPortThatCannotBeConnectedToPasses() {
        assertThat(NetworkSmokeMain.notConnected(connectedAfter("0.000000"), "jupyter-pod").isSuccess()).isTrue();
    }

    @Test
    void aNotebookPortThatCanBeConnectedToFails() {
        // curl times out still, the VNC server never closing the connection
        ExecResult result = NetworkSmokeMain.notConnected(connectedAfter("0.001234"), "jupyter-pod");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).contains("jupyter-pod").contains("polaris-pod");
    }

    @Test
    void aCurlThatSaysNothingOfTheConnectionFails() {
        ExecResult noCurl = new ExecResult(127, "", "sh: curl: not found", "curl", "polaris-pod");

        assertThat(NetworkSmokeMain.notConnected(noCurl, "jupyter-pod").isSuccess()).isFalse();
    }

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
