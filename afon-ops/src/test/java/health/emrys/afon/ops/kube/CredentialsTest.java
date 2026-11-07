package health.emrys.afon.ops.kube;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commands run in pods carry credentials, and are logged and echoed in errors, so they must not keep them.
 */
class CredentialsTest {

    @Test
    void thePasswordArgOfACommandIsRedacted() {
        String command = "wget -qO- --auth-no-challenge --user=will --password=p5ssw0rd --header=Content-Type: application/json "
                         + "http://gitea-http:3000/api/v1/user/repos";

        assertThat(Credentials.redact(command))
            .contains("--user=will --password=<redacted> --header=Content-Type: application/json")
            .doesNotContain("p5ssw0rd");
    }

    @Test
    void thePasswordOfAUrlIsRedactedWithTheUserAndHostKept() {
        assertThat(Credentials.redact("git ls-remote http://will:p5ssw0rd@gitea-http:3000/will/test-repo.git"))
            .isEqualTo("git ls-remote http://will:<redacted>@gitea-http:3000/will/test-repo.git");
        assertThat(Credentials.redact("fatal: unable to access 'https://phil:s3cr3t!@gitea/phil/x.git/'"))
            .doesNotContain("s3cr3t");
    }

    @Test
    void thePasswordOfAJdbcUrlIsRedacted() {
        assertThat(Credentials.redact("jdbc:postgresql://host/?user=postgres&password=p@ss&ssl=true"))
            .isEqualTo("jdbc:postgresql://host/?user=postgres&password=<redacted>&ssl=true");
    }

    @Test
    void anEnvVarAssignmentOfASecretIsRedacted() {
        assertThat(Credentials.redact("PGPASSWORD=hunter2 psql -h db")).isEqualTo("PGPASSWORD=<redacted> psql -h db");
        assertThat(Credentials.redact("export AWS_SECRET_ACCESS_KEY=abc/def+ghi")).isEqualTo("export AWS_SECRET_ACCESS_KEY=<redacted>");
    }

    @Test
    void whatHasNoCredentialIsLeftAsItIs() {
        String command = "curl -L -I --connect-timeout 3 --max-time 3 https://gcp-polaris.emrys2.click";

        assertThat(Credentials.redact(command)).isEqualTo(command);
        assertThat(Credentials.redact(null)).isNull();
        assertThat(Credentials.redact("")).isEmpty();
    }

    @Test
    void anExecResultDoesNotPrintTheCredentialsOfItsCommandOrOutput() {
        ExecResult result = new ExecResult(1, "", "fatal: could not read from http://will:p5ssw0rd@gitea-http/x.git",
                                           "git clone http://will:p5ssw0rd@gitea-http/x.git", "jupyter-will");

        assertThat(result.toString()).doesNotContain("p5ssw0rd").contains("jupyter-will").contains("exitCode=1");
    }
}
