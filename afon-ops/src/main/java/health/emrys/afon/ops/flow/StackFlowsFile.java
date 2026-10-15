package health.emrys.afon.ops.flow;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackComponents;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import health.emrys.afon.config.StackRole;
import health.emrys.afon.ops.steps.StackAction;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * The file defining the flows, {@code stack-flows.yaml}: bundled in the jar, unless the user has a file of that name in the
 * config dir, which replaces it entirely, not merged with it.  As for {@code stacks.yaml}, the system property
 * {@link #PROPERTY} names another file to use instead, or {@link #BUNDLED} for only the bundled one whatever the config dir has,
 * eg for the unit tests and for the build.
 * <p>
 * It only says which stacks each item is for, as a stack id, or the role or component of a stack of a cloud, so that it is
 * parsed without the stacks: finding the stacks is for {@link StackFlows}.
 */
final class StackFlowsFile {

    private static final Logger logger = getLogger(StackFlowsFile.class);

    static final String FILE = "stack-flows.yaml";
    static final String PROPERTY = "afon.stack-flows";
    static final String BUNDLED = "bundled";
    static final String DEFAULT_RESOURCE = "/" + FILE;

    private static final List<String> STACK_KEYS = List.of("stack", "cloud", "role", "component", "scenario");

    /**
     * The stack of an item, by one of: its id; the role it plays in a cloud; a component it deploys in a cloud.
     */
    record StackRef(StackId id, AfonCloud cloud, StackRole role, Class<? extends StackComponent> component) {
    }

    /**
     * An item of a flow, before its stack is found.
     */
    record ItemSpec(StackAction action, StackRef stack, String scenario) {
    }

    private StackFlowsFile() {
    }

    /**
     * @param named     the value of {@link #PROPERTY}, if set
     * @param configDir the config dir
     * @return the file to load, or {@code null} for the bundled one: that named, if any, else that of the config dir if there is one
     * @throws IllegalStateException if the file named is not there
     */
    static Path file(String named, String configDir) {
        if (named != null && !named.isBlank()) {
            if (named.trim().equals(BUNDLED)) {
                return null;
            }
            if (!Files.isRegularFile(Path.of(named))) {
                throw new IllegalStateException("Stack flows not found: " + named + " (" + PROPERTY + ")");
            }
            return Path.of(named);
        }
        Path override = Path.of(configDir, FILE);
        return Files.isRegularFile(override) ? override : null;
    }

    /**
     * @param file the user's file, or {@code null} for the bundled one
     * @return the flows by name, in order
     * @throws IllegalStateException if the file cannot be read or does not define the flows correctly
     */
    static Map<String, List<ItemSpec>> load(Path file) {
        try {
            if (file != null) {
                logger.info("Loading stack flows from {}, overriding the bundled {}", file, FILE);
                return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            }
            try (InputStream in = StackFlowsFile.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Stack flows not found: " + DEFAULT_RESOURCE);
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), DEFAULT_RESOURCE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read stack flows " + (file != null ? file : DEFAULT_RESOURCE)
                                            + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes the bundled file to the config dir, for the user to edit, which then replaces the bundled one.
     *
     * @return the file written
     * @throws IllegalStateException if there is a file already, which is never overwritten, or it cannot be written
     */
    static Path exportBundled(Path configDir) {
        Path file = configDir.resolve(FILE);
        try (InputStream in = StackFlowsFile.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Stack flows not found: " + DEFAULT_RESOURCE);
            }
            if (Files.exists(file)) {
                throw new IllegalStateException(String.format(
                    "%s exists already, so is not overwritten: remove it first to export the bundled flows again", file));
            }
            Files.createDirectories(configDir);
            Files.write(file, in.readAllBytes());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * @param yaml   the content of the file
     * @param source the file's name, for messages
     * @throws IllegalStateException if the flows are not defined correctly
     */
    static Map<String, List<ItemSpec>> parse(String yaml, String source) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<String, Object> root = map(new Yaml(new SafeConstructor(options)).load(yaml), "the file");
            onlyKeys(root, "the file", "flows");
            if (root.get("flows") == null) {
                throw new IllegalArgumentException("no 'flows' defined");
            }
            Map<String, List<ItemSpec>> flows = new LinkedHashMap<>();
            map(root.get("flows"), "flows").forEach((name, items) -> flows.put(name, flow(name, items)));
            return flows;
        } catch (RuntimeException e) {
            throw new IllegalStateException(String.format("Invalid stack flows %s: %s", source, e.getMessage()), e);
        }
    }

    private static List<ItemSpec> flow(String name, Object items) {
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(String.format("flow %s must be a list of at least one item", name));
        }
        List<ItemSpec> specs = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            specs.add(item(String.format("flow %s item %d", name, i + 1), list.get(i)));
        }
        return specs;
    }

    private static ItemSpec item(String where, Object value) {
        Map<String, Object> item = map(value, where);
        if (item.size() != 1) {
            throw new IllegalArgumentException(String.format(
                "%s must be one action (delete, deploy, setup or run) with its stack, not %s", where, item.keySet()));
        }
        String actionName = item.keySet().iterator().next();
        StackAction action = StackAction.of(actionName);
        if (action == null) {
            throw new IllegalArgumentException(String.format(
                "%s has unknown action '%s', expected one of %s", where, actionName, Arrays.toString(StackAction.values()).toLowerCase()));
        }
        Map<String, Object> fields = map(item.get(actionName), where + " " + actionName);
        onlyKeys(fields, where + " " + actionName, STACK_KEYS.toArray(String[]::new));
        String scenario = text(fields, "scenario");
        if (action == StackAction.RUN && scenario == null) {
            throw new IllegalArgumentException(where + " is a run, so needs a scenario");
        }
        if (action != StackAction.RUN && scenario != null) {
            throw new IllegalArgumentException(String.format("%s is a %s, which has no scenario to run", where, actionName));
        }
        return new ItemSpec(action, stackRef(where, fields), scenario);
    }

    private static StackRef stackRef(String where, Map<String, Object> fields) {
        String stack = text(fields, "stack");
        String cloud = text(fields, "cloud");
        String role = text(fields, "role");
        String component = text(fields, "component");
        if (stack != null) {
            if (cloud != null || role != null || component != null) {
                throw new IllegalArgumentException(where + " names its stack by id, so has no cloud, role or component too");
            }
            return new StackRef(StackId.parse(stack), null, null, null);
        }
        if (cloud == null || (role == null) == (component == null)) {
            throw new IllegalArgumentException(where + " needs a stack: either 'stack', or a 'cloud' with a 'role' or a 'component'");
        }
        AfonCloud afonCloud;
        try {
            afonCloud = AfonCloud.valueOf(cloud.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("%s has unknown cloud '%s'", where, cloud));
        }
        if (role != null) {
            StackRole stackRole = StackRole.of(role).orElseThrow(() -> new IllegalArgumentException(String.format(
                "%s has unknown role '%s', expected one of %s", where, role, Arrays.toString(StackRole.values()))));
            return new StackRef(null, afonCloud, stackRole, null);
        }
        Class<? extends StackComponent> type = StackComponents.find(component).orElseThrow(() -> new IllegalArgumentException(
            String.format("%s has unknown component '%s', expected one of %s", where, component, StackComponents.names()))).get().getClass();
        return new StackRef(null, afonCloud, null, type);
    }

    private static String text(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Map<String, Object> map(Object value, String where) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " must be a map");
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
