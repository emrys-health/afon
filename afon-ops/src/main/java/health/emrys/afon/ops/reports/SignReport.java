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
 * The record of {@code docker-image --sign}, which it writes to a file, as JSON or Markdown by the file's extension, for a CI job to keep:
 * what was signed, with which key, and when, even when signing stopped on an error.
 *
 * @param cloud    the cloud whose key and registry were used
 * @param registry the registry the images that are not ours were mirrored to
 * @param key      the reference of the cosign key that signed, not the key itself
 * @param signedAt when signing was done
 * @param signed   the images signed, in order
 * @param error    why signing stopped before every image was signed, or {@code null}
 */
public record SignReport(String cloud, String registry, String key, Instant signedAt, List<DockerImageSigner.Signed> signed, String error) {

    boolean passed() {
        return error == null;
    }

    /**
     * Writes the record, as JSON if the file is named {@code *.json}, else as Markdown, creating the dir it is in.
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
            throw new IllegalStateException("Cannot write the record " + file + ": " + e.getMessage(), e);
        }
    }

    String toJson() throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cloud", cloud);
        report.put("registry", registry);
        report.put("key", key);
        report.put("signedAt", signedAt.toString());
        report.put("passed", passed());
        report.put("signedCount", signed.size());
        report.put("signed", signed);
        report.put("error", error);
        return new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .writeValueAsString(report) + "\n";
    }

    String toMarkdown() {
        StringBuilder md = new StringBuilder("# Docker image signing\n\n");
        md.append(String.format("%s: %d images signed.%n%n", passed() ? "**SIGNED**" : "**FAILED**", signed.size()));
        md.append(String.format("- Cloud: %s%n- Registry: %s%n- Key: %s%n- Signed at: %s%n%n", cell(cloud), cell(registry), cell(key), signedAt));
        if (error != null) {
            md.append(String.format("**Stopped on an error**: %s%n%n", cell(error)));
        }
        if (!signed.isEmpty()) {
            md.append("| Image | Source | Signed | Mirrored |\n|---|---|---|---|\n");
            signed.forEach(image -> md.append(String.format("| %s | `%s` | `%s` | %s |%n", cell(image.name()), image.source(), image.signed(),
                                                            image.mirrored() ? "yes" : "no")));
        }
        return md.toString();
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ").trim();
    }
}
