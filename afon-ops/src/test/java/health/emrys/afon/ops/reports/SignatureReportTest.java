package health.emrys.afon.ops.reports;

import health.emrys.afon.cdk.DockerImageSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureReportTest {

    private static final Instant WHEN = Instant.parse("2026-10-29T10:00:00Z");

    private SignatureReport report(String error) {
        return new SignatureReport("AWS", "123.dkr.ecr.eu-west-2.amazonaws.com/emrys", WHEN, List.of(
            new DockerImageSigner.Verified("spark", "emryshealth/spark@sha256:aaa", null),
            new DockerImageSigner.Verified("keycloak", "reg/keycloak/keycloak@sha256:bbb", error)));
    }

    @Test
    void passesOnlyIfEveryImageVerifies() {
        assertThat(report(null).passed()).isTrue();
        assertThat(report("not signed").passed()).isFalse();
    }

    @Test
    void writesMarkdownOrJsonByTheExtension(@TempDir Path dir) throws IOException {
        report("not signed").write(dir.resolve("out/signatures.md"));
        report("not signed").write(dir.resolve("signatures.json"));

        String md = Files.readString(dir.resolve("out/signatures.md"));
        assertThat(md).contains("**FAILED**: 2 images checked, 1 failed.").contains("| keycloak | `reg/keycloak/keycloak@sha256:bbb` | not signed |")
                      .contains("| spark | `emryshealth/spark@sha256:aaa` | ok |");
        String json = Files.readString(dir.resolve("signatures.json"));
        assertThat(json).contains("\"passed\" : false").contains("\"failedCount\" : 1").contains("\"status\" : \"failed\"")
                        .contains("\"error\" : \"not signed\"");
    }

    @Test
    void refusesAFileThatIsNeitherJsonNorMarkdown(@TempDir Path dir) {
        assertThatThrownBy(() -> report(null).write(dir.resolve("out.txt"))).isInstanceOf(IllegalArgumentException.class);
    }
}
