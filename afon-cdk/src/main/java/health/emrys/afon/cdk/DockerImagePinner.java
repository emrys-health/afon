package health.emrys.afon.cdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up the digests of the images of a {@code docker-images.yaml} in their registries and writes them to the file, which is
 * otherwise left as it is, comments included, as only the {@code digest} lines are edited.
 */
public final class DockerImagePinner {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DIGEST_LINE = Pattern.compile("^ {4}digest:.*$");
    private static final Pattern TAG_LINE = Pattern.compile("^ {4}tag:\\s*(.*?)\\s*$");
    private static final int LOOKUP_TIMEOUT_SECONDS = 120;

    /**
     * Looks up the digest of an image in its registry.
     */
    @FunctionalInterface
    public interface DigestResolver {
        /**
         * @param reference the image and tag, eg {@code quay.io/keycloak/keycloak:26.6.3}
         * @return the digest of the manifest list or manifest the tag points to, {@code sha256:<64 hex digits>}
         * @throws IOException if it cannot be looked up
         */
        String digest(String reference) throws IOException;
    }

    /**
     * What pinning did to an image.
     *
     * @param name      the name of the image
     * @param reference the image and tag looked up
     * @param before    the digest the file had, or {@code null} if none
     * @param after     the digest now
     */
    public record Change(String name, String reference, String before, String after) {
        public boolean isChanged() {
            return !after.equals(before);
        }
    }

    /**
     * The result of comparing the digest an image is pinned to with what its tag points to now.
     *
     * @param name      the name of the image
     * @param reference the image and tag looked up
     * @param pinned    the digest the file has, or {@code null} if none
     * @param registry  the digest the registry says the tag points to now, or {@code null} if it could not be looked up
     * @param error     why it could not, if so
     */
    public record Check(String name, String reference, String pinned, String registry, String error) {
        public boolean isOk() {
            return pinned != null && pinned.equals(registry);
        }
    }

    private DockerImagePinner() {
    }

    /**
     * Compares the digests of the images with those that their tags point to now, which tells if a tag was changed without its digest, or a
     * tag was published again by its registry.  Writes nothing.
     *
     * @param images   the images, as defined in the file
     * @param names    the images to check, even those with no digest, which then fail; or none for those that have a digest
     * @param resolver looks up the digests
     * @return the result for each image, by name
     * @throws IllegalStateException if a name is not one of the images
     */
    public static List<Check> verify(Map<String, DockerImageDefinitions.Definition> images, List<String> names, DigestResolver resolver) {
        for (String name : names) {
            if (!images.containsKey(name)) {
                throw new IllegalStateException(String.format("Unknown image '%s', expected one of %s", name, images.keySet()));
            }
        }
        List<Check> checks = new ArrayList<>();
        for (DockerImageDefinitions.Definition image : images.values()) {
            if (names.isEmpty() ? image.digest() != null : names.contains(image.name())) {
                String reference = image.image() + ":" + image.tag();
                try {
                    checks.add(new Check(image.name(), reference, image.digest(), resolver.digest(reference), null));
                } catch (IOException e) {
                    checks.add(new Check(image.name(), reference, image.digest(), null, e.getMessage()));
                }
            }
        }
        return checks;
    }

    /**
     * A resolver that asks Docker (buildx) for the digest, which needs it installed, and the registry to be reachable.
     */
    public static DigestResolver docker() {
        return reference -> {
            try {
                Process process = new ProcessBuilder("docker", "buildx", "imagetools", "inspect", reference,
                                                     "--format", "{{.Manifest.Digest}}")
                    .redirectErrorStream(true).start();
                String output;
                try (var in = process.getInputStream()) {
                    output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                if (!process.waitFor(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Timed out looking up the digest of " + reference);
                }
                if (process.exitValue() != 0 || !DIGEST.matcher(output).matches()) {
                    throw new IOException("Cannot look up the digest of " + reference + ": " + output);
                }
                return output;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted looking up the digest of " + reference, e);
            }
        };
    }

    /**
     * Writes to the file the digest that the tag of each image points to now, in place of the digest it has, or, for a
     * named image that has none, after its tag.  Nothing is written if any lookup fails or the result is not valid.
     *
     * @param file         the images file to edit
     * @param names        the images to pin, or none for those of the file that have a digest already, which refreshes them
     * @param resolver     looks up the digests
     * @param placeholders resolves the {@code ${key}} placeholders of the tags
     * @return what was done for each image, in the order of the file
     * @throws IllegalStateException if the file is not valid, a name is not in it, an image has a tag that is a placeholder, whose
     *                               digest would outlive the version it stands for, or a digest cannot be looked up
     */
    public static List<Change> pin(Path file, List<String> names, DigestResolver resolver, UnaryOperator<String> placeholders) {
        try {
            String yaml = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, DockerImageDefinitions.Definition> images = DockerImageDefinitions.parse(yaml, file.toString(), placeholders);
            for (String name : names) {
                if (!images.containsKey(name)) {
                    throw new IllegalStateException(String.format("Unknown image '%s' in %s, expected one of %s", name, file, images.keySet()));
                }
            }
            List<String> lines = new ArrayList<>(yaml.lines().toList());
            List<Change> changes = new ArrayList<>();
            for (DockerImageDefinitions.Definition image : images.values().stream().sorted(order(lines)).toList()) {
                if (names.isEmpty() ? image.digest() != null : names.contains(image.name())) {
                    changes.add(pin(lines, image, resolver));
                }
            }
            String edited = String.join("\n", lines) + (yaml.endsWith("\n") ? "\n" : "");
            // not written unless it is still valid
            DockerImageDefinitions.parse(edited, file.toString(), placeholders);
            if (changes.stream().anyMatch(Change::isChanged)) {
                Files.writeString(file, edited, StandardCharsets.UTF_8);
            }
            return changes;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or write " + file + ": " + e.getMessage(), e);
        }
    }

    private static java.util.Comparator<DockerImageDefinitions.Definition> order(List<String> lines) {
        return java.util.Comparator.comparingInt(image -> blockStart(lines, image.name()));
    }

    private static int blockStart(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^ {2}" + Pattern.quote(name) + ":\\s*$")) {
                return i;
            }
        }
        throw new IllegalStateException("Image '" + name + "' not found in the file");
    }

    private static Change pin(List<String> lines, DockerImageDefinitions.Definition image, DigestResolver resolver) throws IOException {
        if (image.unpinned() != null) {
            throw new IllegalStateException(String.format("%s is unpinned: %s", image.name(), image.unpinned()));
        }
        int start = blockStart(lines, image.name());
        int end = start + 1;
        while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).startsWith("   "))) {
            end++;
        }
        int tagLine = -1;
        int digestLine = -1;
        for (int i = start + 1; i < end; i++) {
            Matcher tag = TAG_LINE.matcher(lines.get(i));
            if (tag.matches()) {
                tagLine = i;
                if (tag.group(1).contains("${")) {
                    throw new IllegalStateException(String.format(
                        "%s is unpinned: its tag %s follows something else, so a digest would outlive the version",
                        image.name(), tag.group(1)));
                }
            } else if (DIGEST_LINE.matcher(lines.get(i)).matches()) {
                digestLine = i;
            }
        }
        String reference = image.image() + ":" + image.tag();
        String digest = resolver.digest(reference);
        String line = "    digest: " + digest;
        if (digestLine >= 0) {
            lines.set(digestLine, line);
        } else {
            lines.add(tagLine + 1, line);
        }
        return new Change(image.name(), reference, image.digest(), digest);
    }
}
