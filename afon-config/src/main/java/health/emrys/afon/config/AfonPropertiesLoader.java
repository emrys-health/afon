package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static org.slf4j.LoggerFactory.getLogger;

public class AfonPropertiesLoader {

    /**
     * Application properties live in {@code afon.properties}.
     * Cloud-specific properties live alongside it in
     * {@code afon-<cloud>.properties} (e.g. {@code afon-aws.properties}).
     */
    public static final String APP_PROPERTIES_FILE = "afon.properties";
    /**
     * Sensitive secrets live in {@code env.properties}
     * Cloud-specific secrets live alongside it in
     * {@code env-<cloud>.properties} (e.g. {@code env-aws.properties}).
     */
    public static final String ENV_PROPERTIES_FILE = "env.properties";
    /**
     * System property selecting properties for a given {@code afon-<cloud>.properties} and
     * {@code env-<cloud>.properties} to load; when unset, every cloud's files are loaded together,
     * since some deployments (e.g. cross-cloud federation) need more than one cloud's properties at the same time.
     */
    public static final String CLOUD_SYSTEM_PROPERTY = "afon.cloud";
    private static final List<String> ALL_CLOUDS = List.of("aws", "azure", "gcp");
    private static final Logger       logger     = getLogger(AfonPropertiesLoader.class);

    static {
        try {
            logger.debug("Initializing properties");
            Class.forName(AfonProperties.class.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private final Properties  properties;
    private final Set<String> confidentialKeys = new HashSet<>();

    public AfonPropertiesLoader() {
        properties = new Properties();
    }

    private void loadProperties() {
        String dir = System.getProperty("afon.home", "..");
        String configDir = dir + "/config";
        try {
            loadPropertiesFromDir(configDir);
        } catch (IOException e) {
            logger.warn("Failed to load properties files in {}", configDir);
            String altDir = "config";
            try {
                // ...or in the IDE?
                loadPropertiesFromDir(altDir);
            } catch (IOException ex) {
                logger.error("Failed to load properties files  in {}", altDir);
                throw new RuntimeException(ex); // urgh, the properties file not found
            }
        }
    }

    private void loadPropertiesFromDir(String dir) throws IOException {
        logger.info("Loading properties from {}", dir);
        for (String file : propertyFiles()) {
            Properties props = loadPropertiesFromFile(dir, file);
            properties.putAll(props);
            if (file.startsWith("env")) {
                confidentialKeys.addAll(props.keySet().stream().map(x -> x.toString()).toList());
            }
        }
        for (String property : AfonProperties.getAllNames()) {
            if (System.getProperty(property) != null) {
                logger.debug("Using the environment variables to define property '{}'", property);
                properties.put(property, System.getProperty(property));
            }
        }
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

    /**
     * Lists the {@code afon.properties} and {@code env.properties} and its cloud-specific file(s).
     * {@link AfonPropertiesLoader#CLOUD_SYSTEM_PROPERTY} selects a single cloud; otherwise every cloud's file
     * is listed, since some deployments (e.g. cross-cloud federation) need more than one cloud's
     * properties in the same run.
     */
    private List<String> propertyFiles() {
        List<String> files = new ArrayList<>();
        files.addAll(withCloudVariants(APP_PROPERTIES_FILE, "afon"));
        files.addAll(withCloudVariants(ENV_PROPERTIES_FILE, "env"));
        return files;
    }

    private List<String> withCloudVariants(String commonFile, String prefix) {
        List<String> files = new ArrayList<>();
        files.add(commonFile);
        String cloud = System.getProperty(CLOUD_SYSTEM_PROPERTY);
        if (cloud != null && !cloud.isBlank()) {
            files.add(cloudPropertiesFile(prefix, cloud));
        } else {
            ALL_CLOUDS.forEach(c -> files.add(cloudPropertiesFile(prefix, c)));
        }
        return files;
    }

    private static String cloudPropertiesFile(String prefix, String cloud) {
        return prefix + "-" + cloud.toLowerCase() + ".properties";
    }

    public Properties getConfidentialProperties() {
        Properties copy = new Properties();
        confidentialKeys.stream().forEach(prop -> copy.put(prop, properties.getProperty(prop)));
        return copy;
    }

    public Properties getProperties() {
        if ( properties.isEmpty() ){
            // lazy loading and only once
            loadProperties();
        }
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    public Properties getProperties(String overrideFile) throws IOException {
        Properties properties = getProperties();
        properties.putAll(loadPropertiesFromFile(overrideFile));
        return properties;
    }

}
