package health.emrys.afon.ops.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import health.emrys.afon.cdk.DockerImageSigner;

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
 * The report of {@code docker-image --verify --signatures}, which it writes to a file, as JSON or Markdown by the file's extension, for a CI
 * job to keep: each image checked, and whether it has a signature that verifies with the public key.
 *
 * @param cloud     the cloud whose registry and public key were used
 * @param registry  the registry the images that are not ours were mirrored to
 * @param checkedAt when the images were checked
 * @param checks    the result for each image, in order
 */
public record SignatureReport(String cloud, String registry, Instant checkedAt, List<DockerImageSigner.Verified> checks) {

    public boolean passed() {
        return checks.stream().allMatch(DockerImageSigner.Verified::isOk);
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
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cloud", cloud);
        report.put("registry", registry);
        report.put("checkedAt", checkedAt.toString());
        report.put("passed", passed());
        report.put("checkedCount", checks.size());
        report.put("failedCount", checks.stream().filter(check -> !check.isOk()).count());
        report.put("checks", checks.stream().map(check -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", check.name());
            entry.put("image", check.image());
            entry.put("status", check.isOk() ? "ok" : "failed");
            entry.put("error", check.error());
            return entry;
        }).toList());
        return new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .writeValueAsString(report) + "\n";
    }

    String toMarkdown() {
        long failed = checks.stream().filter(check -> !check.isOk()).count();
        StringBuilder md = new StringBuilder("# Docker image signatures\n\n");
        md.append(String.format("%s: %d images checked, %d failed.%n%n", passed() ? "**PASSED**" : "**FAILED**", checks.size(), failed));
        md.append(String.format("- Cloud: %s%n- Registry: %s%n- Checked at: %s%n%n", cell(cloud), cell(registry), checkedAt));
        if (!checks.isEmpty()) {
            md.append("| Image | Checked | Result |\n|---|---|---|\n");
            checks.forEach(check -> md.append(String.format("| %s | `%s` | %s |%n", cell(check.name()), check.image(),
                                                            check.isOk() ? "ok" : cell(check.error()))));
        }
        return md.toString();
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ").trim();
    }
}
