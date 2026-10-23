package health.emrys.afon.ops.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the images that the Helm charts of a stack pull, which {@code docker-images.yaml} does not list as they are the charts' own.
 * It reads the {@code helm_release} resources of the plan that was synthesised for the stack, so with the values the stack gives them,
 * and renders each chart with {@code helm template}, which needs no cluster, then collects the images of the manifests.
 */
public final class ChartImages {

    /**
     * Where in the manifests the image was found.
     */
    public static final String IMAGE = "image";
    /**
     * An image named in an argument of a container, like {@code --acme-http01-solver-image=}.
     */
    public static final String ARGUMENT = "argument";

    private static final Pattern IMAGE_ARGUMENT = Pattern.compile("^--[\\w.-]*image[\\w.-]*=(\\S+)$");
    private static final Pattern REFERENCE = Pattern.compile("^[\\w.-]+(:\\d+)?(/[\\w.-]+)*(:[\\w.-]+)?(@sha256:[0-9a-f]{64})?$");
    private static final String DEFAULT_KUBE_VERSION = "1.30.0";
    private static final int RENDER_TIMEOUT_SECONDS = 120;

    /**
     * An image of a chart.
     *
     * @param stack   the stack whose plan has the release
     * @param release the name of the release
     * @param chart   the file of the chart
     * @param image   the reference, as in the manifest
     * @param kind    {@link #IMAGE} or {@link #ARGUMENT}
     * @param test    whether it is in a pod that only {@code helm test} runs, which no deploy does
     */
    public record Found(String stack, String release, String chart, String image, String kind, boolean test) {

        public boolean isPinned() {
            return image.contains("@sha256:");
        }

        /**
         * @return the repository, ie the reference without its tag and digest
         */
        public String repository() {
            return repositoryOf(image);
        }

        /**
         * @return the tag, or {@code null} if there is none
         */
        public String tag() {
            String withoutDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
            int slash = withoutDigest.lastIndexOf('/');
            int colon = withoutDigest.lastIndexOf(':');
            return colon > slash ? withoutDigest.substring(colon + 1) : null;
        }
    }

    /**
     * Renders a chart, like {@code helm template}.
     */
    @FunctionalInterface
    public interface Renderer {
        /**
         * @param chart     the chart file
         * @param release   the name of the release
         * @param namespace the namespace of the release, if any
         * @param values    the values files, as YAML texts, in order
         * @param sets      the {@code --set} values, as {@code name=value}
         * @return the manifests, as a stream of YAML documents
         * @throws IOException if it cannot render the chart
         */
        String render(Path chart, String release, String namespace, List<String> values, List<String> sets) throws IOException;
    }

    private ChartImages() {
    }

    /**
     * A renderer that runs {@code helm template}, which needs helm installed, but no cluster.
     */
    public static Renderer helm() {
        return (chart, release, namespace, values, sets) -> {
            List<String> command = new ArrayList<>(List.of("helm", "template", release, chart.toString(), "--kube-version", DEFAULT_KUBE_VERSION));
            if (namespace != null && !namespace.isBlank()) {
                command.addAll(List.of("--namespace", namespace));
            }
            List<Path> files = new ArrayList<>();
            try {
                for (String text : values) {
                    Path file = Files.createTempFile("afon-chart-values-", ".yaml");
                    files.add(file);
                    Files.writeString(file, text, StandardCharsets.UTF_8);
                    command.addAll(List.of("-f", file.toString()));
                }
                sets.forEach(set -> command.addAll(List.of("--set", set)));
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output;
                try (var in = process.getInputStream()) {
                    output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!process.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Timed out rendering " + chart.getFileName());
                }
                if (process.exitValue() != 0) {
                    throw new IOException("helm template failed for " + chart.getFileName() + ": " + firstLine(output));
                }
                return output;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted rendering " + chart.getFileName(), e);
            } finally {
                for (Path file : files) {
                    Files.deleteIfExists(file);
                }
            }
        };
    }

    /**
     * What came of a release: its images, or why they could not be found.
     */
    public record Release(String stack, String release, String chart, List<Found> images, String error) {
    }

    /**
     * @param stack    the id of the stack
     * @param stackDir the dir the stack was synthesised into, with its {@code cdk.tf.json} and the charts as assets
     * @param renderer renders the charts
     * @return what each helm release of the plan pulls, in the order of the plan
     * @throws IOException if the plan cannot be read
     */
    public static List<Release> inspect(String stack, Path stackDir, Renderer renderer) throws IOException {
        Path plan = stackDir.resolve("cdk.tf.json");
        if (!Files.isRegularFile(plan)) {
            throw new IOException("No plan in " + stackDir + ": synthesise the stack first, eg with validate-stack " + stack);
        }
        JsonNode releases = new ObjectMapper().readTree(plan.toFile()).path("resource").path("helm_release");
        List<Release> result = new ArrayList<>();
        for (var entry : releases.properties()) {
            JsonNode release = entry.getValue();
            String name = release.path("name").asText(entry.getKey());
            Path chart = stackDir.resolve(release.path("chart").asText());
            List<String> values = new ArrayList<>();
            release.path("values").forEach(text -> values.add(text.asText()));
            List<String> sets = new ArrayList<>();
            release.path("set").forEach(set -> sets.add(set.path("name").asText() + "=" + escapeSet(set.path("value").asText())));
            try {
                String manifests = renderer.render(chart, name, release.path("namespace").asText(null), values, sets);
                result.add(new Release(stack, name, chart.getFileName().toString(), imagesIn(stack, name, chart.getFileName().toString(), manifests), null));
            } catch (IOException e) {
                result.add(new Release(stack, name, chart.getFileName().toString(), List.of(), e.getMessage()));
            }
        }
        return result;
    }

    /**
     * @param manifests the YAML documents that a chart rendered to
     * @return the images in them, once each
     */
    static List<Found> imagesIn(String stack, String release, String chart, String manifests) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Set<Found> found = new LinkedHashSet<>();
        for (Object document : new Yaml(new SafeConstructor(options)).loadAll(manifests)) {
            if (document instanceof Map<?, ?> map) {
                boolean test = isTestHook(map);
                collect(map, stack, release, chart, test, found);
            }
        }
        return List.copyOf(found);
    }

    private static boolean isTestHook(Map<?, ?> document) {
        if (document.get("metadata") instanceof Map<?, ?> metadata && metadata.get("annotations") instanceof Map<?, ?> annotations) {
            Object hook = annotations.get("helm.sh/hook");
            return hook != null && hook.toString().contains("test");
        }
        return false;
    }

    private static void collect(Object node, String stack, String release, String chart, boolean test, Set<Found> found) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("image".equals(key) && value instanceof String text && isReference(text)) {
                    found.add(new Found(stack, release, chart, text, IMAGE, test));
                } else {
                    collect(value, stack, release, chart, test, found);
                }
            });
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text) {
                    Matcher argument = IMAGE_ARGUMENT.matcher(text);
                    if (argument.matches() && isReference(argument.group(1))) {
                        found.add(new Found(stack, release, chart, argument.group(1), ARGUMENT, test));
                    }
                } else {
                    collect(item, stack, release, chart, test, found);
                }
            }
        }
    }

    private static boolean isReference(String text) {
        return !text.isBlank() && !text.contains("{{") && REFERENCE.matcher(text).matches();
    }

    /**
     * @return the reference without its tag and digest
     */
    static String repositoryOf(String image) {
        String withoutDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = withoutDigest.lastIndexOf('/');
        int colon = withoutDigest.lastIndexOf(':');
        return colon > slash ? withoutDigest.substring(0, colon) : withoutDigest;
    }

    private static String escapeSet(String value) {
        return value.replace("\\", "\\\\").replace(",", "\\,");
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }

    /**
     * @return the images by the name they are in docker-images.yaml under, for those the file lists, by repository, so that a chart image
     * that is listed there is shown to be
     */
    public static Map<String, String> listedBy(Map<String, health.emrys.afon.cdk.DockerImageDefinitions.Definition> images) {
        Map<String, String> byRepository = new LinkedHashMap<>();
        images.values().forEach(image -> byRepository.putIfAbsent(repositoryOf(image.image()), image.name()));
        return byRepository;
    }
}
