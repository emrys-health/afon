package health.emrys.afon.cdk;

import health.emrys.afon.cdk.aws.AwsLoggingComponent;
import health.emrys.afon.cdk.azure.AzureLoggingComponent;
import health.emrys.afon.cdk.components.*;
import health.emrys.afon.config.*;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static health.emrys.afon.config.ConfigUtils.configDir;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The stacks, each composed of {@link StackComponent}s that run on whatever cloud the stack is deployed to.  A stack
 * is its {@link StackId} and the list of its components, so a new stack, eg of a different
 * subset of components, or on another cloud, is a new entry in the YAML that defines them.
 * <p>
 * The stacks are loaded at runtime from {@code stacks.yaml}, bundled in the jar, unless the user has a file of that name in the
 * config dir ({@link ConfigUtils#configDir()}), which replaces it entirely, not merged with it.  A stack may {@code extend} another
 * of its cloud, to start with its components, and a component may be deployed {@code when} or {@code unless} a
 * boolean property of the stack's cloud, eg {@code KEYCLOAK_EXTERNAL} for {@code <CLOUD>_KEYCLOAK_EXTERNAL}.  The
 * components are those of {@link StackComponents}.  A file that does not define the stacks correctly fails when
 * they are first used, with a message naming the file and the problem.
 */
public final class StackDefinitions {

    /**
     * The file in the config dir that replaces the bundled stacks.
     */
    public static final String STACKS_FILE = "stacks.yaml";

    /**
     * System property naming a stacks file to use instead of those of the config dir and the bundled ones, for the unit
     * tests to define their own stacks.
     */
    public static final String STACKS_PROPERTY = "afon.stacks";

    /**
     * The value of {@link #STACKS_PROPERTY} for only the bundled stacks, whatever the config dir has, eg for the build.
     */
    public static final String BUNDLED = "bundled";
    static final String DEFAULT_RESOURCE = "/stacks.yaml";

    private static final Logger logger = getLogger(StackDefinitions.class);

    private StackDefinitions() {
    }

    private static Map<StackId, StackDefinition> loaded;
    private static Path loadedFrom;

    /**
     * @return the stacks, loaded when first used, and again if the config dir has changed since, so that a file that
     * fails to load fails each use, not only the first
     */
    private static synchronized Map<StackId, StackDefinition> definitions() {
        Path file = stacksFile(System.getProperty(STACKS_PROPERTY), configDir());
        if (loaded == null || !Objects.equals(file, loadedFrom)) {
            loaded = load(file);
            loadedFrom = file;
        }
        return loaded;
    }

    /**
     * @param named     the value of {@link #STACKS_PROPERTY}, if set
     * @param configDir the config dir
     * @return the stacks file to load, or {@code null} for the bundled one: that named, if any, else that of the config dir if
     * there is one
     * @throws IllegalStateException if the file named is not there
     */
    static Path stacksFile(String named, String configDir) {
        if (named != null && !named.isBlank()) {
            if (named.trim().equals(BUNDLED)) {
                return null;
            }
            if (!Files.isRegularFile(Path.of(named))) {
                throw new IllegalStateException("Stack definitions not found: " + named + " (" + STACKS_PROPERTY + ")");
            }
            return Path.of(named);
        }
        Path override = Path.of(configDir, STACKS_FILE);
        return Files.isRegularFile(override) ? override : null;
    }

    /**
     * @param file the user's stacks file, or {@code null} for the bundled one
     */
    static Map<StackId, StackDefinition> load(Path file) {
        try {
            if (file != null) {
                logger.info("Loading stack definitions from {}, overriding the bundled {}", file, STACKS_FILE);
                return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            }
            try (InputStream in = StackDefinitions.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Stack definitions not found: " + DEFAULT_RESOURCE);
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), DEFAULT_RESOURCE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read stack definitions " + (file != null ? file : DEFAULT_RESOURCE)
                                            + ": " + e.getMessage(), e);
        }
    }

    /**
     * A component of a stack, deployed if its property, when it has one, is {@code deployedIf}.
     */
    private record Entry(String name, Supplier<StackComponent> component, Class<? extends StackComponent> type,
                         String property, boolean deployedIf) {
        boolean isDeployed(AfonCloud cloud, AfonPropertyProvider properties) {
            return property == null
                   || Boolean.parseBoolean(properties.getCloudValue(cloud, property, "false").trim()) == deployedIf;
        }
    }

    private record Spec(StackId extended, StackRole role, Boolean pinnedEgressIp, List<Entry> entries) {
    }

    /**
     * @param yaml   the content of the stacks file
     * @param source the file's name, for messages
     * @throws IllegalStateException if the stacks are not defined correctly
     */
    static Map<StackId, StackDefinition> parse(String yaml, String source) {
        try {
            Map<StackId, Spec> specs = new TreeMap<>();
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<String, Object> root = map(new Yaml(new SafeConstructor(options)).load(yaml), "the file", "stacks");
            if (root.get("stacks") == null) {
                throw new IllegalArgumentException("no 'stacks' defined");
            }
            Map<String, Object> stacks = map(root.get("stacks"), "stacks", "stack ids");
            stacks.forEach((key, value) -> {
                StackId id = StackId.parse(key);
                if (id.isAll()) {
                    throw new IllegalArgumentException(String.format("%s is not a stack, as %s stands for every stack of the cloud", key, id));
                }
                if (specs.put(id, spec(key, value)) != null) {
                    throw new IllegalArgumentException(String.format("%s is defined more than once", id));
                }
            });
            Map<StackId, StackDefinition> definitions = new TreeMap<>();
            specs.keySet().forEach(id -> definitions.put(id, definition(id, specs)));
            checkRolesAreUnique(definitions.values());
            return definitions;
        } catch (RuntimeException e) {
            throw new IllegalStateException(String.format("Invalid stack definitions %s: %s", source, e.getMessage()), e);
        }
    }

    @SuppressWarnings("unchecked")
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

    private static Spec spec(String stack, Object value) {
        Map<String, Object> map = map(value, stack, "extends, role, pinnedEgressIp and components");
        onlyKeys(map, stack, "extends", "role", "pinnedEgressIp", "components");
        StackRole role = null;
        if (map.get("role") != null) {
            String named = String.valueOf(map.get("role"));
            role = StackRole.of(named).orElseThrow(() -> new IllegalArgumentException(String.format(
                "%s has unknown role '%s', expected one of %s", stack, named, Arrays.toString(StackRole.values()))));
        }
        StackId extended = map.get("extends") == null ? null : StackId.parse(String.valueOf(map.get("extends")));
        Object pinned = map.get("pinnedEgressIp");
        if (pinned != null && !(pinned instanceof Boolean)) {
            throw new IllegalArgumentException(stack + " pinnedEgressIp must be true or false");
        }
        Object components = map.get("components");
        if (components != null && !(components instanceof List<?>)) {
            throw new IllegalArgumentException(stack + " components must be a list");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object component : components == null ? List.of() : (List<?>) components) {
            entries.add(entry(stack, component));
        }
        return new Spec(extended, role, (Boolean) pinned, entries);
    }

    private static Entry entry(String stack, Object value) {
        Map<String, Object> map = value instanceof Map<?, ?>
            ? map(value, stack + " component", "name, when and unless")
            : new LinkedHashMap<>(Map.of("name", String.valueOf(value)));
        onlyKeys(map, stack + " component", "name", "when", "unless");
        String name = String.valueOf(map.get("name"));
        Supplier<StackComponent> component = StackComponents.find(name).orElseThrow(() ->
            new IllegalArgumentException(String.format("%s has unknown component '%s', expected one of %s",
                                                       stack, name, StackComponents.names())));
        if (map.containsKey("when") && map.containsKey("unless")) {
            throw new IllegalArgumentException(String.format("%s component %s has both when and unless", stack, name));
        }
        boolean when = map.containsKey("when");
        Object property = when ? map.get("when") : map.get("unless");
        return new Entry(name, component, component.get().getClass(), property == null ? null : String.valueOf(property), when);
    }

    private static StackDefinition definition(StackId id, Map<StackId, Spec> specs) {
        List<Entry> entries = new ArrayList<>();
        List<StackId> chain = new ArrayList<>();
        for (StackId next = id; next != null; next = specs.get(next).extended()) {
            if (chain.contains(next)) {
                throw new IllegalArgumentException(String.format("%s extends itself, via %s", id, chain));
            }
            if (!chain.isEmpty() && next.cloud() != id.cloud()) {
                throw new IllegalArgumentException(String.format(
                    "%s extends %s, but stacks of different clouds are independent", id, next));
            }
            if (!specs.containsKey(next)) {
                throw new IllegalArgumentException(String.format("%s extends %s, which is not defined", chain.get(chain.size() - 1), next));
            }
            chain.add(next);
        }
        // the extended stack first, and the stack decides over what it extends
        Boolean decided = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Spec spec = specs.get(chain.get(i));
            entries.addAll(spec.entries());
            decided = spec.pinnedEgressIp() != null ? spec.pinnedEgressIp() : decided;
        }
        Set<Class<? extends StackComponent>> declared = new LinkedHashSet<>();
        entries.forEach(entry -> declared.add(entry.type()));
        // the role is the stack's own, not that of what it extends
        return new StackDefinition(id, specs.get(id).role(), decided == null || decided, properties -> entries.stream()
            .filter(entry -> entry.isDeployed(id.cloud(), properties)).map(entry -> entry.component().get()).toList(),
            Collections.unmodifiableSet(declared));
    }

    private static void checkRolesAreUnique(Collection<StackDefinition> definitions) {
        Map<String, StackId> byRole = new HashMap<>();
        for (StackDefinition definition : definitions) {
            if (definition.role() == null) {
                continue;
            }
            StackId other = byRole.put(definition.id().cloud() + " " + definition.role(), definition.id());
            if (other != null) {
                throw new IllegalArgumentException(String.format(
                    "%s and %s both have the role %s, which a cloud has once", other, definition.id(), definition.role()));
            }
        }
    }

    /**
     * @return whether the stacks of the cloud otherwise deploying their own KeyCloak use instead the
     * one at {@code OIDC_DOMAIN_NAME} that the cloud's keycloak stack deploys, as configured by
     * {@code <CLOUD>_KEYCLOAK_EXTERNAL}, {@code false} when unset or the cloud has no such property
     */
    public static boolean isKeyCloakExternal(AfonCloud cloud, AfonPropertyProvider properties) {
        return Boolean.parseBoolean(properties.getCloudValue(cloud, "KEYCLOAK_EXTERNAL", "false").trim());
    }

    /**
     * Writes the bundled stacks file to the config dir, for the user to edit, which then replaces the bundled one.
     *
     * @param configDir the dir to write {@link #STACKS_FILE} to, created if missing
     * @return the file written
     * @throws IllegalStateException if there is a file already, which is never overwritten, or it cannot be written
     */
    public static Path exportBundled(Path configDir) {
        Path file = configDir.resolve(STACKS_FILE);
        try (InputStream in = StackDefinitions.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Stack definitions not found: " + DEFAULT_RESOURCE);
            }
            if (Files.exists(file)) {
                throw new IllegalStateException(String.format(
                    "%s exists already, so is not overwritten: remove it first to export the bundled stacks again", file));
            }
            Files.createDirectories(configDir);
            Files.write(file, in.readAllBytes());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * @return where the stacks are loaded from: the file in the config dir, else the bundled one
     */
    public static String source() {
        definitions();
        return loadedFrom != null ? loadedFrom.toString() : "bundled " + STACKS_FILE;
    }

    /**
     * Loads the stacks if not yet loaded, for the commands to fail before doing anything if they are not valid.  The message
     * names the file and the problem.
     *
     * @throws IllegalStateException if the stacks are not valid
     */
    public static void checkLoads() {
        definitions();
    }

    /**
     * @return the stack of the cloud with the role, or empty if there is none
     */
    public static Optional<StackDefinition> withRole(AfonCloud cloud, StackRole role) {
        return definitions().values().stream()
                            .filter(definition -> definition.id().cloud() == cloud && definition.role() == role).findFirst();
    }

    /**
     * @return the first stack of the cloud that may deploy the kind of component, in order of the ids, or empty if there is none
     */
    public static Optional<StackDefinition> declaring(AfonCloud cloud, Class<? extends StackComponent> type) {
        return definitions().values().stream()
                            .filter(definition -> definition.id().cloud() == cloud && definition.declares(type)).findFirst();
    }

    /**
     * @return the definition of the stack, or empty if there is none
     */
    public static Optional<StackDefinition> find(StackId id) {
        return Optional.ofNullable(definitions().get(id));
    }

    /**
     * @throws IllegalArgumentException if there is no such stack
     */
    public static StackDefinition get(StackId id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("No such stack: " + id));
    }

    /**
     * @return the stacks, in order of their ids
     */
    public static Collection<StackDefinition> all() {
        return definitions().values();
    }
}
