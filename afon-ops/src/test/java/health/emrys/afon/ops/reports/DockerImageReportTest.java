package health.emrys.afon.ops.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.DockerImagePinner.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImageReportTest {

    private static final String OLD = "sha256:" + "a".repeat(64);
    private static final String NEW = "sha256:" + "b".repeat(64);

    private final DockerImageReport report = new DockerImageReport("the images in effect", Instant.parse("2026-10-22T18:00:00Z"),
        List.of(new DockerImageReport.Entry("keycloak", "quay.io/keycloak/keycloak:26.6.3", "ok", OLD, OLD, null),
                new DockerImageReport.Entry("curl", "curlimages/curl:8.11.1", "mismatch", OLD, NEW, null),
                new DockerImageReport.Entry("spark", "a/spark:1", "error", OLD, null, "429 | too many\nrequests")),
        List.of(new DockerImageReport.Unpinned("trino", "only its tag is used")));

    @Test
    void jsonHasTheSummaryTheImagesAndTheUnpinned() throws IOException {
        JsonNode json = new ObjectMapper().readTree(report.toJson());
        assertThat(json.get("passed").asBoolean()).isFalse();
        assertThat(json.get("verifiedAt").asText()).isEqualTo("2026-10-22T18:00:00Z");
        assertThat(json.at("/summary/checked").asInt()).isEqualTo(3);
        assertThat(json.at("/summary/failed").asInt()).isEqualTo(2);
        assertThat(json.at("/summary/unpinned").asInt()).isEqualTo(1);
        assertThat(json.at("/checked/0/status").asText()).isEqualTo("ok");
        assertThat(json.at("/checked/1/registryDigest").asText()).isEqualTo(NEW);
        assertThat(json.at("/checked/2/error").asText()).contains("429");
        assertThat(json.at("/checked/0/error").isMissingNode()).isTrue();
        assertThat(json.at("/unpinned/0/name").asText()).isEqualTo("trino");
    }

    @Test
    void markdownHasTheResultATableOfTheImagesAndOneOfTheUnpinned() {
        String md = report.toMarkdown();
        assertThat(md).contains("**FAILED**: 3 checked, 2 failed, 1 unpinned and not checked.")
            .contains("| keycloak | quay.io/keycloak/keycloak:26.6.3 | ok | `" + OLD + "` | `" + OLD + "` |")
            .contains("| curl | curlimages/curl:8.11.1 | **MISMATCH** |")
            // one line, and the pipe escaped
            .contains("**ERROR**: 429 \\| too many requests")
            .contains("## Unpinned, not checked").contains("| trino | only its tag is used |");
    }

    @Test
    void writesJsonOrMarkdownByTheExtensionAndRejectsOthers(@TempDir Path dir) throws IOException {
        report.write(dir.resolve("out/verify.json"));
        report.write(dir.resolve("verify.md"));
        assertThat(Files.readString(dir.resolve("out/verify.json"))).startsWith("{");
        assertThat(Files.readString(dir.resolve("verify.md"))).startsWith("# Docker image verification");
        assertThatThrownBy(() -> report.write(dir.resolve("verify.txt")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("*.json or *.md");
    }

    @Test
    void entryStatusFollowsTheCheck() {
        assertThat(DockerImageReport.Entry.of(new Check("a", "a:1", OLD, OLD, null)).status()).isEqualTo("ok");
        assertThat(DockerImageReport.Entry.of(new Check("a", "a:1", OLD, NEW, null)).status()).isEqualTo("mismatch");
        // an image named that has no digest
        assertThat(DockerImageReport.Entry.of(new Check("a", "a:1", null, NEW, null)).status()).isEqualTo("unpinned");
        assertThat(DockerImageReport.Entry.of(new Check("a", "a:1", OLD, null, "unreachable")).status()).isEqualTo("error");
    }

    @Test
    void anUnpinnedImageNamedIsAFailureShownAsUnpinned() throws IOException {
        var unpinned = new DockerImageReport("the images in effect", Instant.parse("2026-10-22T18:00:00Z"),
            List.of(DockerImageReport.Entry.of(new Check("curl", "curlimages/curl:8.11.1", null, NEW, null))), List.of());
        assertThat(unpinned.passed()).isFalse();
        assertThat(unpinned.toMarkdown()).contains("| curl | curlimages/curl:8.11.1 | **UNPINNED** |  | `" + NEW + "` |");
        JsonNode json = new ObjectMapper().readTree(unpinned.toJson());
        assertThat(json.at("/checked/0/status").asText()).isEqualTo("unpinned");
        assertThat(json.at("/checked/0/pinnedDigest").isMissingNode()).isTrue();
        assertThat(json.at("/summary/failed").asInt()).isEqualTo(1);
    }
}
