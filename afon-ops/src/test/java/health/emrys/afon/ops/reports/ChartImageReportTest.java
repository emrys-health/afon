package health.emrys.afon.ops.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.ops.synth.ChartImages;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChartImageReportTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private final ChartImageReport report = new ChartImageReport(List.of("aws-full"), List.of(
        new ChartImages.Release("aws-full", "trino", "trino-1.tgz", List.of(
            new ChartImages.Found("aws-full", "trino", "trino-1.tgz", "trinodb/trino:481", ChartImages.IMAGE, false),
            new ChartImages.Found("aws-full", "trino", "trino-1.tgz", "bats/bats:v1", ChartImages.IMAGE, true)), null),
        new ChartImages.Release("aws-full", "cilium", "cilium-1.tgz", List.of(
            new ChartImages.Found("aws-full", "cilium", "cilium-1.tgz", "quay.io/cilium/cilium:v1@" + DIGEST, ChartImages.IMAGE, false)), null),
        new ChartImages.Release("aws-full", "loki", "loki-1.tgz", List.of(), "helm failed | badly")),
        Map.of("trinodb/trino", "trino"), Instant.parse("2026-10-24T10:00:00Z"));

    @Test
    void entriesLeaveOutTheImagesOfHelmTestPodsUnlessAsked() {
        assertThat(report.entries(false)).extracting(ChartImageReport.Entry::image).containsExactly("trinodb/trino:481", "quay.io/cilium/cilium:v1@" + DIGEST);
        assertThat(report.entries(true)).hasSize(3);
        assertThat(report.entries(false).get(0).listedAs()).isEqualTo("trino");
        assertThat(report.failed()).isEqualTo(1);
    }

    @Test
    void jsonHasTheSummaryTheImagesAndTheErrors() throws IOException {
        JsonNode json = new ObjectMapper().readTree(report.toJson());
        assertThat(json.at("/summary/images").asInt()).isEqualTo(2);
        assertThat(json.at("/summary/pinned").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/helmTestOnly").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/releasesNotRendered").asInt()).isEqualTo(1);
        assertThat(json.at("/images/0/listedAs").asText()).isEqualTo("trino");
        assertThat(json.at("/errors/0/release").asText()).isEqualTo("loki");
    }

    @Test
    void markdownHasATableOfTheImagesAndOneOfTheErrors() {
        String md = report.toMarkdown();
        assertThat(md).contains("2 images, 1 pinned by digest")
            .contains("| aws-full | trino | `trinodb/trino:481` | no | trino |")
            .contains("| aws-full | cilium | `quay.io/cilium/cilium:v1@" + DIGEST + "` | yes |  |")
            .contains("| aws-full | loki | helm failed \\| badly |").doesNotContain("bats/bats");
    }
}
