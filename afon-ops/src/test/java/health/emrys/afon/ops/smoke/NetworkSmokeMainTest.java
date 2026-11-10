package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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

    private static IllegalStateException refused(int code, String body) {
        // as HttpResponses.parseAccessToken words it
        return new IllegalStateException("Failed to obtain access token, code: " + code + ", response: " + body);
    }

    @Test
    void theAdminSideRefusedByTheIngressPasses() {
        assertThat(NetworkSmokeMain.adminSideRefused("oauth.example", () -> { throw refused(403, "Forbidden"); }).isSuccess()).isTrue();
    }

    @Test
    void anAdminTokenObtainedFromHereFails() {
        ExecResult result = NetworkSmokeMain.adminSideRefused("oauth.example", () -> "a-token");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).contains("Obtained an admin token").contains("oauth.example");
    }

    @Test
    void anAdminTokenRequestThatKeyCloakItselfAnsweredFails() {
        // a wrong password: KeyCloak was reached
        ExecResult result = NetworkSmokeMain.adminSideRefused(
            "oauth.example", () -> { throw refused(401, "{\"error\":\"invalid_grant\"}"); });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).contains("HTTP 401");
    }

    @Test
    void anAdminTokenRequestThatGotNoResponseFails() {
        assertThat(NetworkSmokeMain.adminSideRefused("oauth.example", () -> { throw new IOException("Connection refused"); })
                                   .isSuccess()).isFalse();
    }

    @Test
    void anUnknownUserRefusedByKeyCloakShowsTheLoginSideIsOpen() {
        assertThat(NetworkSmokeMain.loginSideOpen(
            "oauth.example", () -> { throw refused(401, "{\"error\":\"invalid_client\"}"); }).isSuccess()).isTrue();
    }

    @Test
    void aLoginRefusedByTheIngressFails() {
        ExecResult result = NetworkSmokeMain.loginSideOpen("oauth.example", () -> { throw refused(403, "Forbidden"); });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).contains("open to all");
    }

    @Test
    void aLoginThatGotNoResponseFails() {
        assertThat(NetworkSmokeMain.loginSideOpen("oauth.example", () -> { throw new IOException("timed out"); }).isSuccess()).isFalse();
    }

    @Test
    void onlyARefusalHasACode() {
        assertThat(NetworkSmokeMain.refusalCodeOf(refused(502, "Bad Gateway"))).isEqualTo(502);
        assertThat(NetworkSmokeMain.refusalCodeOf(new IOException("code: 403"))).isEqualTo(-1);
        assertThat(NetworkSmokeMain.refusalCodeOf(new IllegalStateException("No KeyCloak bootstrap admin credentials"))).isEqualTo(-1);
    }
}
