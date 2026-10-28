package health.emrys.afon.ops.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.DockerImageSigner.Signed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignReportTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final List<Signed> SIGNED = List.of(
        new Signed("spark", "emryshealth/spark@" + DIGEST, "emryshealth/spark@" + DIGEST, false),
        new Signed("keycloak", "quay.io/keycloak/keycloak@" + DIGEST, "r.io/emrys/keycloak/keycloak@" + DIGEST, true));
    private static final Instant AT = Instant.parse("2026-10-28T12:00:00Z");

    private final SignReport report = new SignReport("AWS", "r.io/emrys", "awskms:///alias/sign", AT, SIGNED, null);

    @Test
    void jsonHasTheCloudTheKeyAndWhatWasSigned() throws IOException {
        JsonNode json = new ObjectMapper().readTree(report.toJson());
        assertThat(json.get("passed").asBoolean()).isTrue();
        assertThat(json.get("cloud").asText()).isEqualTo("AWS");
        assertThat(json.get("key").asText()).isEqualTo("awskms:///alias/sign");
        assertThat(json.get("signedAt").asText()).isEqualTo("2026-10-28T12:00:00Z");
        assertThat(json.get("signedCount").asInt()).isEqualTo(2);
        assertThat(json.at("/signed/1/mirrored").asBoolean()).isTrue();
        assertThat(json.at("/signed/1/signed").asText()).isEqualTo("r.io/emrys/keycloak/keycloak@" + DIGEST);
        assertThat(json.get("error")).isNull();
    }

    @Test
    void recordsWhatWasSignedBeforeAnErrorStoppedIt() throws IOException {
        var failed = new SignReport("AWS", "r.io/emrys", "k", AT, SIGNED.subList(0, 1), "cosign failed: denied | no access\nretry");
        JsonNode json = new ObjectMapper().readTree(failed.toJson());
        assertThat(json.get("passed").asBoolean()).isFalse();
        assertThat(json.get("signedCount").asInt()).isEqualTo(1);
        assertThat(json.get("error").asText()).contains("cosign failed");
        assertThat(failed.toMarkdown()).contains("**FAILED**: 1 images signed.")
            .contains("**Stopped on an error**: cosign failed: denied \\| no access retry");
    }

    @Test
    void markdownHasATableOfTheImages() {
        assertThat(report.toMarkdown()).contains("**SIGNED**: 2 images signed.").contains("- Key: awskms:///alias/sign")
            .contains("| spark | `emryshealth/spark@" + DIGEST + "` | `emryshealth/spark@" + DIGEST + "` | no |")
            .contains("| keycloak | `quay.io/keycloak/keycloak@" + DIGEST + "` | `r.io/emrys/keycloak/keycloak@" + DIGEST + "` | yes |");
    }

    @Test
    void writesJsonOrMarkdownByTheExtensionAndRefusesOthers(@TempDir Path dir) throws IOException {
        report.write(dir.resolve("out/sign.json"));
        report.write(dir.resolve("sign.md"));
        assertThat(Files.readString(dir.resolve("out/sign.json"))).startsWith("{");
        assertThat(Files.readString(dir.resolve("sign.md"))).startsWith("# Docker image signing");
        assertThatThrownBy(() -> report.write(dir.resolve("sign.txt"))).isInstanceOf(IllegalArgumentException.class);
    }
}
