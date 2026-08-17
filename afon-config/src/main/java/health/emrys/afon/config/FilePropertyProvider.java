package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * File-based implementation of {@link AfonPropertyProvider}.
 *
 * Application properties live in {@code afon.properties}.
 * Cloud-specific properties live alongside it in
 * {@code afon-<cloud>.properties} (e.g. {@code afon-aws.properties}).
 *
 * Sensitive secrets live in {@code secret.properties}
 * Cloud-specific secrets live alongside it in
 * {@code secret-<cloud>.properties} (e.g. {@code secret-aws.properties}).
 * The properties files are loaded from the ConfigUtils.configDir().
 *
 * When {@link ConfigConstants#CLOUD_SYSTEM_PROPERTY} is unset, every cloud's files are loaded together,
 * since some deployments (e.g. cross-cloud federation) need more than one cloud's properties at the same time.
 *
 * Any property will be overridden if it is provided as system property, e.g. {@code -DCLUSTER_NAME=<new-name>}.
 */
public class FilePropertyProvider implements AfonPropertyProvider {

    private static final Logger logger = getLogger(FilePropertyProvider.class);

    /**
     * Property types
     */
    private static final List<String> ALL_TYPES = List.of("afon", "secret");

    /**
     * Cloud types
     */
    private static final List<String> ALL_CLOUDS = List.of("aws", "azure", "gcp");

    private final List<String> types;
    private final Properties  properties;
    private final Set<String> secretKeys = new HashSet<>();

    public FilePropertyProvider() {
        this(ALL_TYPES);
    }

    public FilePropertyProvider(List<String> types) {
        this.types = types;
        properties = new Properties();
    }

    @Override
    public Properties getProperties() {
        loadPropertiesLazily();
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    @Override
    public Properties getProperties(String overrideFile) throws IOException {
        Properties properties = getProperties();
        properties.putAll(loadPropertiesFromFile(overrideFile));
        return properties;
    }

    @Override
    public Properties getSecretProperties() {
        loadPropertiesLazily();
        Properties secret = new Properties();
        secretKeys.stream().forEach(prop -> secret.put(prop, properties.getProperty(prop)));
        return secret;
    }

    @Override
    public String getValue(AfonProperty property) {
        loadPropertiesLazily();
        return property.getValueFrom(properties);
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        loadPropertiesLazily();
        return property.getValueFrom(properties, defaultValue);
    }

    private void loadPropertiesLazily() {
        if ( properties.isEmpty() ){
            // lazy loading and only once
            loadProperties();
        }
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
            String     filename = dir + "/" + file;
            Properties props = loadPropertiesFromFile(filename);
            properties.putAll(props);
            if (file.startsWith("secret")) {
                secretKeys.addAll(props.keySet().stream().map(x -> x.toString()).toList());
            }
        }
        overrideWithSystemProperties();
    }

    private @NonNull Properties loadPropertiesFromFile(String filename) throws IOException {
        try {
            Properties properties = loadPropertiesFromStream(new FileInputStream(filename));
            logger.debug("Loaded {} items from {}", properties.size(), filename);
            return properties;
        } catch ( IOException e ){
            logger.warn("Failed to load properties from {}", filename);
            throw e;
        }
    }

    @NonNull Properties loadPropertiesFromStream(InputStream inputStream) throws IOException {
        Properties properties = new Properties();
        properties.load(inputStream);
        return properties;
    }

    /**
     * Override properties with the value provided via system properties, if any.
     */
    private void overrideWithSystemProperties() {
        for (String property : AfonProperty.getAllNames()) {
            if (System.getProperty(property) != null) {
                logger.debug("Using the system variables to define property '{}'", property);
                properties.put(property, System.getProperty(property));
            }
        }
    }

    /**
     * Lists the {@code afon.properties} and {@code secret.properties} and its cloud-specific file(s).
     * {@link ConfigConstants#CLOUD_SYSTEM_PROPERTY} selects a single cloud; otherwise every cloud's file
     * is listed, since some deployments (e.g. cross-cloud federation) need more than one cloud's
     * properties in the same run.
     */
    private List<String> propertyFiles(String type) {
        List<String> files = new ArrayList<>();
        // first add common properties
        files.add(fileName(type, null));
        // then add could properties
        String cloud = System.getProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY);
        if (cloud != null && !cloud.isBlank()) {
            files.add(fileName(type, cloud));
        } else {
            ALL_CLOUDS.forEach(c -> files.add(fileName(type, c)));
        }
        return files;
    }

    private static String fileName(String type, String cloud) {
        if (cloud != null) {
            return type + "-" + cloud.toLowerCase() + ".properties";
        } else {
            return type + ".properties";
        }
    }

}
