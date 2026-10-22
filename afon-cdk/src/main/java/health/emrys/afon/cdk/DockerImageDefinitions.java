package health.emrys.afon.cdk;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static health.emrys.afon.config.ConfigUtils.configDir;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The Docker images, loaded at runtime from {@code docker-images.yaml}, bundled in the jar, unless the user has a file
 * of that name in the config dir ({@link health.emrys.afon.config.ConfigUtils#configDir()}), which replaces it entirely, not merged
 * with it.  A file that does not define the images correctly fails when they are first used, with a message naming
 * the file and the problem.  See {@link DockerImages} for the images.
 */
public final class DockerImageDefinitions {

    /**
     * The file in the config dir that replaces the bundled images.
     */
    public static final String IMAGES_FILE = "docker-images.yaml";

    /**
     * System property naming an images file to use instead of that of the config dir and the bundled one.
     */
    public static final String IMAGES_PROPERTY = "afon.docker-images";

    /**
     * The value of {@link #IMAGES_PROPERTY} for only the bundled images, whatever the config dir has.
     */
    public static final String BUNDLED = "bundled";
    static final String DEFAULT_RESOURCE = "/docker-images.yaml";

    private static final Logger logger = getLogger(DockerImageDefinitions.class);
    private static final Set<String> CATEGORIES = Set.of("first-party", "upstream", "third-party");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * A Docker image, by its repository, tag and optionally the digest it is pinned to.
     */
    public record Definition(String name, String image, String tag, String digest, String category) {

        /**
         * @return the reference to pull the image by: {@code image:tag}, followed by {@code @digest} if pinned
         */
        public String reference() {
            return image + ":" + tag + (digest == null ? "" : "@" + digest);
        }
    }

    private DockerImageDefinitions() {
    }

    /**
     * @param placeholders resolves the {@code ${key}} placeholders of the tags
     * @return the images by name, from the file in the config dir or named by {@link #IMAGES_PROPERTY} if any, else the
     * bundled one
     * @throws IllegalStateException if the images cannot be loaded or are not valid
     */
    static Map<String, Definition> load(UnaryOperator<String> placeholders) {
        return load(imagesFile(System.getProperty(IMAGES_PROPERTY), configDir()), placeholders);
    }

    /**
     * @param named     the value of {@link #IMAGES_PROPERTY}, if set
     * @param configDir the config dir
     * @return the images file to load, or {@code null} for the bundled one
     * @throws IllegalStateException if the file named is not there
     */
    static Path imagesFile(String named, String configDir) {
        if (named != null && !named.isBlank()) {
            if (named.trim().equals(BUNDLED)) {
                return null;
            }
            if (!Files.isRegularFile(Path.of(named))) {
                throw new IllegalStateException("Docker images not found: " + named + " (" + IMAGES_PROPERTY + ")");
            }
            return Path.of(named);
        }
        Path override = Path.of(configDir, IMAGES_FILE);
        return Files.isRegularFile(override) ? override : null;
    }

    /**
     * @param file the user's images file, or {@code null} for the bundled one
     */
    static Map<String, Definition> load(Path file, UnaryOperator<String> placeholders) {
        try {
            if (file != null) {
                logger.info("Loading Docker images from {}, overriding the bundled {}", file, IMAGES_FILE);
                return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString(), placeholders);
            }
            try (InputStream in = DockerImageDefinitions.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Docker images not found: " + DEFAULT_RESOURCE);
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), DEFAULT_RESOURCE, placeholders);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Docker images " + (file != null ? file : DEFAULT_RESOURCE)
                                            + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes the bundled images file to the config dir, for the user to edit, which then replaces the bundled one.
     *
     * @param configDir the dir to write {@link #IMAGES_FILE} to, created if missing
     * @return the file written
     * @throws IllegalStateException if there is a file already, which is never overwritten, or it cannot be written
     */
    public static Path exportBundled(Path configDir) {
        Path file = configDir.resolve(IMAGES_FILE);
        try (InputStream in = DockerImageDefinitions.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Docker images not found: " + DEFAULT_RESOURCE);
            }
            if (Files.exists(file)) {
                throw new IllegalStateException(String.format(
                    "%s exists already, so is not overwritten: remove it first to export the bundled images again", file));
            }
            Files.createDirectories(configDir);
            Files.write(file, in.readAllBytes());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * @param yaml         the content of the images file
     * @param source       the file's name, for messages
     * @param placeholders resolves the {@code ${key}} placeholders of the tags
     * @throws IllegalStateException if the images are not defined correctly
     */
    static Map<String, Definition> parse(String yaml, String source, UnaryOperator<String> placeholders) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<String, Object> root = map(strings(options).load(yaml), "the file", "images");
            onlyKeys(root, "the file", "images");
            if (root.get("images") == null) {
                throw new IllegalArgumentException("no 'images' defined");
            }
            Map<String, Definition> definitions = new TreeMap<>();
            map(root.get("images"), "images", "image names").forEach((name, value) ->
                definitions.put(name, definition(name, value, placeholders)));
            return Collections.unmodifiableMap(definitions);
        } catch (RuntimeException e) {
            throw new IllegalStateException(String.format("Invalid Docker images %s: %s", source, e.getMessage()), e);
        }
    }

    /**
     * @return a YAML parser that reads every plain scalar as the string it is written as, not as the number or boolean it
     * looks like, as all the values of the file are strings, whose tags would lose their zeros as numbers, eg 3.10 as 3.1
     */
    private static Yaml strings(LoaderOptions options) {
        Resolver resolver = new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                // none, so that nothing is resolved to another type than a string
            }
        };
        return new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(), options, resolver);
    }

    private static Definition definition(String name, Object value, UnaryOperator<String> placeholders) {
        Map<String, Object> map = map(value, name, "image, tag, digest and category");
        onlyKeys(map, name, "image", "tag", "digest", "category");
        String image = required(map, name, "image");
        String tag = resolve(name, required(map, name, "tag"), placeholders);
        String digest = optional(map, "digest");
        if (digest != null && !DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException(String.format("%s digest '%s' must be sha256:<64 hex digits>", name, digest));
        }
        String category = optional(map, "category");
        if (category != null && !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException(String.format("%s has unknown category '%s', expected one of %s",
                                                             name, category, new TreeSet<>(CATEGORIES)));
        }
        return new Definition(name, image, tag, digest, category);
    }

    private static String optional(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static String required(Map<String, Object> map, String name, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(String.format("%s has no '%s'", name, key));
        }
        return String.valueOf(value).trim();
    }

    private static String resolve(String name, String tag, UnaryOperator<String> placeholders) {
        var matcher = PLACEHOLDER.matcher(tag);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.apply(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException(String.format("%s tag has unknown placeholder '%s'", name, matcher.group()));
            }
            matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static Map<String, Object> map(Object value, String where, String expected) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(String.format("%s must be a map of %s", where, expected));
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        map.forEach((key, v) -> checked.put(String.valueOf(key), v));
        return checked;
    }

    private static void onlyKeys(Map<String, Object> map, String where, String... allowed) {
        List<String> permitted = List.of(allowed);
        map.keySet().stream().filter(key -> !permitted.contains(key)).findFirst().ifPresent(key -> {
            throw new IllegalArgumentException(String.format("%s has unknown '%s', expected one of %s", where, key, permitted));
        });
    }
}
