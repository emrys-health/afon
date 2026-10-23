package health.emrys.afon.ops.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import health.emrys.afon.ops.synth.ChartImages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The report of {@code docker-image --charts}, which it writes to a file, as JSON or Markdown by the file's extension.
 *
 * @param stacks     the stacks inspected
 * @param releases   what each helm release pulls
 * @param listedAs   the name in docker-images.yaml of the repositories it lists, to show the chart images that it does
 * @param reportedAt when the charts were rendered
 */
public record ChartImageReport(List<String> stacks, List<ChartImages.Release> releases, Map<String, String> listedAs, Instant reportedAt) {

    /**
     * An image of a release, as reported.
     */
    public record Entry(String stack, String release, String chart, String image, String kind, String tag, boolean pinned, boolean helmTest,
                        String listedAs) {
    }

    /**
     * @return the images of the releases, not those that only a {@code helm test} runs, unless asked
     */
    public List<Entry> entries(boolean withTests) {
        return releases.stream().flatMap(release -> release.images().stream())
            .filter(found -> withTests || !found.test())
            .map(found -> new Entry(found.stack(), found.release(), found.chart(), found.image(), found.kind(), found.tag(), found.isPinned(),
                                    found.test(), listedAs.get(found.repository())))
            .toList();
    }

    public long failed() {
        return releases.stream().filter(release -> release.error() != null).count();
    }

    /**
     * Writes the report, as JSON if the file is named {@code *.json}, else as Markdown, creating the dir it is in.
     *
     * @throws IllegalArgumentException if the file is not named as a JSON or a Markdown one
     */
    public void write(Path file) {
        DockerImageReport.checkWritable(file);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(DockerImageReport.JSON) ? toJson() : toMarkdown(),
                              StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the report " + file + ": " + e.getMessage(), e);
        }
    }

    String toJson() throws IOException {
        List<Entry> entries = entries(true);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("images", entries.stream().filter(entry -> !entry.helmTest()).count());
        summary.put("pinned", entries.stream().filter(entry -> !entry.helmTest() && entry.pinned()).count());
        summary.put("helmTestOnly", entries.stream().filter(Entry::helmTest).count());
        summary.put("releasesNotRendered", failed());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportedAt", reportedAt.toString());
        report.put("stacks", stacks);
        report.put("summary", summary);
        report.put("images", entries);
        report.put("errors", releases.stream().filter(release -> release.error() != null)
            .map(release -> Map.of("stack", release.stack(), "release", release.release(), "error", release.error())).toList());
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(report) + "\n";
    }

    String toMarkdown() {
        List<Entry> entries = entries(false);
        long pinned = entries.stream().filter(Entry::pinned).count();
        StringBuilder md = new StringBuilder("# Images of the Helm charts\n\n");
        md.append(String.format("%d images, %d pinned by digest, in the releases of %s.%n%n- Reported at: %s%n%n", entries.size(), pinned,
                                String.join(", ", stacks), reportedAt));
        md.append("| Stack | Release | Image | Pinned | In docker-images.yaml |\n|---|---|---|---|---|\n");
        entries.forEach(entry -> md.append(String.format("| %s | %s | `%s` | %s | %s |%n", entry.stack(), entry.release(), entry.image(),
                                                         entry.pinned() ? "yes" : entry.tag() == null ? "**no tag**" : "no",
                                                         entry.listedAs() == null ? "" : entry.listedAs())));
        if (failed() > 0) {
            md.append("\n## Releases that could not be rendered\n\n| Stack | Release | Error |\n|---|---|---|\n");
            releases.stream().filter(release -> release.error() != null).forEach(release -> md.append(String.format(
                "| %s | %s | %s |%n", release.stack(), release.release(), release.error().replace("|", "\\|").replaceAll("\\s*\\R\\s*", " "))));
        }
        return md.toString();
    }
}
