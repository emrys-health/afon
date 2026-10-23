package health.emrys.afon.ops.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import health.emrys.afon.cdk.DockerImageDefinitions;
import health.emrys.afon.cdk.DockerImagePinner;

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
 * The report of {@code docker-image --verify}, which it writes to a file, as JSON or Markdown by the file's extension, for a CI job to keep.
 *
 * @param source     where the images are from: the file, or those in effect
 * @param verifiedAt when they were verified
 * @param checked    the images checked and what came of it
 * @param unpinned   the images with no digest that were not checked, and why
 */
public record DockerImageReport(String source, Instant verifiedAt, List<Entry> checked, List<Unpinned> unpinned) {

    static final String JSON = ".json";
    static final List<String> MARKDOWN = List.of(".md", ".markdown");

    /**
     * @param status {@code ok}, {@code mismatch}, {@code unpinned} (an image named that has no digest) or {@code error}
     */
    public record Entry(String name, String reference, String status, String pinnedDigest, String registryDigest, String error) {
        public static Entry of(DockerImagePinner.Check check) {
            String status = check.isOk() ? "ok" : check.error() != null ? "error" : check.pinned() == null ? "unpinned" : "mismatch";
            return new Entry(check.name(), check.reference(), status, check.pinned(), check.registry(), check.error());
        }
    }

    public record Unpinned(String name, String reason) {
        public static Unpinned of(DockerImageDefinitions.Definition image) {
            return new Unpinned(image.name(), image.unpinned() != null ? image.unpinned() : "no digest in the file");
        }
    }

    boolean passed() {
        return failed() == 0;
    }

    long failed() {
        return checked.stream().filter(entry -> !"ok".equals(entry.status())).count();
    }

    /**
     * @throws IllegalArgumentException if the file is not named as a JSON or a Markdown one
     */
    public static void checkWritable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(JSON) && MARKDOWN.stream().noneMatch(name::endsWith)) {
            throw new IllegalArgumentException(String.format(
                "The report %s must be named *.json or *.md, which sets its format", file));
        }
    }

    /**
     * Writes the report, as JSON if the file is named {@code *.json}, else as Markdown, creating the dir it is in.
     */
    public void write(Path file) {
        checkWritable(file);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JSON) ? toJson() : toMarkdown(),
                              StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the report " + file + ": " + e.getMessage(), e);
        }
    }

    String toJson() throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", checked.size());
        summary.put("failed", failed());
        summary.put("unpinned", unpinned.size());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", source);
        report.put("verifiedAt", verifiedAt.toString());
        report.put("passed", passed());
        report.put("summary", summary);
        report.put("checked", checked);
        report.put("unpinned", unpinned);
        return new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .writeValueAsString(report) + "\n";
    }

    String toMarkdown() {
        StringBuilder md = new StringBuilder("# Docker image verification\n\n");
        md.append(String.format("%s: %d checked, %d failed, %d unpinned and not checked.%n%n",
                                passed() ? "**PASSED**" : "**FAILED**", checked.size(), failed(), unpinned.size()));
        md.append(String.format("- Images: %s%n- Verified at: %s%n%n", cell(source), verifiedAt));
        if (!checked.isEmpty()) {
            md.append("| Image | Reference | Status | Pinned digest | Registry digest |\n|---|---|---|---|---|\n");
            checked.forEach(entry -> md.append(String.format("| %s | %s | %s | %s | %s |%n", cell(entry.name()), cell(entry.reference()),
                                                              status(entry), digest(entry.pinnedDigest()), digest(entry.registryDigest()))));
            md.append('\n');
        }
        if (!unpinned.isEmpty()) {
            md.append("## Unpinned, not checked\n\n| Image | Reason |\n|---|---|\n");
            unpinned.forEach(image -> md.append(String.format("| %s | %s |%n", cell(image.name()), cell(image.reason()))));
        }
        return md.toString();
    }

    private static String status(Entry entry) {
        return switch (entry.status()) {
            case "ok" -> "ok";
            case "mismatch" -> "**MISMATCH**";
            case "unpinned" -> "**UNPINNED**";
            default -> "**ERROR**: " + cell(entry.error());
        };
    }

    private static String digest(String digest) {
        return digest == null ? "" : "`" + digest + "`";
    }

    /**
     * @return the text safe in a table cell: one line, with no pipe to end it
     */
    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ").trim();
    }
}
