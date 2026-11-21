package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logs of the pods that {@code scripts/smoke/test.sh} saves when a stack fails to set up, or a test fails, go to the logs store of a public repository,
 * so they must have no secret in the clear: {@code mask_secrets} of the script, run here by bash as the script runs it.
 */
class SmokeTestDiagnosticsScriptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private String masked(String text) throws IOException, InterruptedException {
        String script = "set -euo pipefail; source <(sed -n '/^mask_secrets() {/,/^}/p' '" + ROOT + "/scripts/smoke/test.sh'); mask_secrets";
        Process process = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start();
        process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }

    @Test
    void masksTheValuesOfPasswordsSecretsAndTokens() throws Exception {
        String out = masked("""
            POSTGRES_PASSWORD=hunter2 ok
            jdbc:postgresql://h/db?user=u&password=hunter3
            git clone https://admin:hunter4@host/x
            {"client_secret":"abc123","name":"keep"}
            password: hunter5
            run --token=hunter6
            """);
        assertThat(out).doesNotContain("hunter", "abc123").contains("\"name\":\"keep\"", "POSTGRES_PASSWORD=<redacted> ok");
    }

    @Test
    void masksBearerTokensAndJwts() throws Exception {
        String out = masked("Authorization: Bearer abc.def-ghi\ntok eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig_nature\n");
        assertThat(out).doesNotContain("abc.def", "eyJ").contains("Bearer <redacted>", "<redacted-jwt>");
    }

    @Test
    void leavesOtherLinesAsTheyAre() throws Exception {
        String line = "java.net.SocketException: Connection reset by peer\n";
        assertThat(masked(line)).isEqualTo(line);
    }
}
