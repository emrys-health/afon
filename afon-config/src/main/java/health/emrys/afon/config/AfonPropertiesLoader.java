package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Properties loader allows the retrieval of properties of different type along with their cloud-variants.
 *
 * Application properties live in {@code afon.properties}.
 * Cloud-specific properties live alongside it in
 * {@code afon-<cloud>.properties} (e.g. {@code afon-aws.properties}).
 *
 * Sensitive secrets live in {@code secret.properties}
 * Cloud-specific secrets live alongside it in
 * {@code secret-<cloud>.properties} (e.g. {@code secret-aws.properties}).
 *
 * The properties files are loaded from the ConfigUtils.configDir().
 */
public class AfonPropertiesLoader {

    private static final Logger logger = getLogger(AfonPropertiesLoader.class);

    /**
     * System property selecting properties for a given {@code afon-<cloud>.properties} and
     * {@code secret-<cloud>.properties} to load; when unset, every cloud's files are loaded together,
     * since some deployments (e.g. cross-cloud federation) need more than one cloud's properties at the same time.
     */
    public static final String CLOUD_SYSTEM_PROPERTY = "afon.cloud";

    /**
     * Property types
     */
    private static final List<String> ALL_TYPES = List.of("afon", "secret");

    /**
     * Cloud types
     */
    private static final List<String> ALL_CLOUDS = List.of("aws", "azure", "gcp");

    static {
        try {
            logger.debug("Initializing properties");
            Class.forName(AfonProperties.class.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private final List<String> types;
    private final Properties  properties;
    private final Set<String> secretKeys = new HashSet<>();

    public AfonPropertiesLoader() {
        this(ALL_TYPES);
    }

    public AfonPropertiesLoader(List<String> types) {
        this.types = types;
        properties = new Properties();
    }

    private void loadProperties() {
        types.forEach(type -> loadProperties(type));
    }

    private void loadProperties(String type) {
        String configDir = ConfigUtils.configDir();

        try {
            loadPropertiesFromDir(type, configDir);
        } catch (IOException e) {
            logger.warn("Failed to load {} properties files in {}", type, configDir);
        }
    }

    private void loadPropertiesFromDir(String type, String dir) throws IOException {
        logger.info("Loading {} properties from {}", type, dir);
        for (String file : propertyFiles(type)) {
            Properties props = loadPropertiesFromFile(dir, file);
            properties.putAll(props);
            if (file.startsWith("secret")) {
                secretKeys.addAll(props.keySet().stream().map(x -> x.toString()).toList());
            }
        }
        for (String property : AfonProperties.getAllNames()) {
            if (System.getProperty(property) != null) {
                logger.debug("Using the environment variables to define property '{}'", property);
                properties.put(property, System.getProperty(property));
            }
        }
    }

    /**
     * Lists the {@code afon.properties} and {@code secret.properties} and its cloud-specific file(s).
     * {@link AfonPropertiesLoader#CLOUD_SYSTEM_PROPERTY} selects a single cloud; otherwise every cloud's file
     * is listed, since some deployments (e.g. cross-cloud federation) need more than one cloud's
     * properties in the same run.
     */
    private List<String> propertyFiles(String type) {
        List<String> files = new ArrayList<>();
        // first add common properties
        files.add(fileName(type, null));
        // then add could properties
        String cloud = System.getProperty(CLOUD_SYSTEM_PROPERTY);
        if (cloud != null && !cloud.isBlank()) {
            files.add(fileName(type, cloud));
        } else {
            ALL_CLOUDS.forEach(c -> files.add(fileName(type, c)));
        }
        return files;
    }

    public Properties getSecretProperties() {
        loadPropertiesLazily();
        Properties secret = new Properties();
        secretKeys.stream().forEach(prop -> secret.put(prop, properties.getProperty(prop)));
        return secret;
    }

    public Properties getProperties() {
        loadPropertiesLazily();
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    private void loadPropertiesLazily() {
        if ( properties.isEmpty() ){
            // lazy loading and only once
            loadProperties();
        }
    }

    public Properties getProperties(String overrideFile) throws IOException {
        Properties properties = getProperties();
        properties.putAll(loadPropertiesFromFile(overrideFile));
        return properties;
    }

    private @NonNull Properties loadPropertiesFromFile(String dir, String file) throws IOException {
        String     filename = dir + "/" + file;
        return loadPropertiesFromFile(filename);
    }

    private @NonNull Properties loadPropertiesFromFile(String filename) throws IOException {
        Properties props    = new Properties();
        try {
            props.load(new FileInputStream(filename));
            logger.info("Loaded {} items from {}", props.size(), filename);
        } catch ( IOException e ){
            logger.warn("Failed to load properties from {}", filename);
            throw e;
        }
        return props;
    }

    private static String fileName(String type, String cloud) {
        if (cloud != null) {
            return type + "-" + cloud.toLowerCase() + ".properties";
        } else {
            return type + ".properties";
        }
    }

}
