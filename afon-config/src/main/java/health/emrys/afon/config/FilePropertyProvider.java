package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;
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
 *
 * The properties files are loaded from the {@link #configDir}, which defaults to {@link ConfigUtils#configDir()}.
 *
 * When {@link ConfigUtils#cloudPropertySet()} is {@code true}, every cloud's files are loaded together,
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

    private final String configDir;
    private final List<String> types;
    private final Properties  properties;
    private final Set<String> secretKeys = new HashSet<>();

    public FilePropertyProvider() {
        this(ConfigUtils.configDir());
    }

    public FilePropertyProvider(String configDir) {
        this(configDir, ALL_TYPES);
    }

    public FilePropertyProvider(String configDir, List<String> types) {
        this.configDir = configDir;
        this.types = types;
        this.properties = new Properties();
    }

    @Override
    public Properties getProperties() {
        loadLazily();
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    @Override
    public Properties getSecretProperties() {
        loadLazily();
        Properties secret = new Properties();
        secretKeys.stream().forEach(prop -> secret.put(prop, properties.getProperty(prop)));
        return secret;
    }

    @Override
    public boolean hasProperty(AfonProperty property) {
        loadLazily();
        return properties.containsKey(property.getName());
    }

    @Override
    public String getValue(AfonProperty property) {
        loadLazily();
        return property.getValueFrom(properties);
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        loadLazily();
        return property.getValueFrom(properties, defaultValue);
    }

    @Override
    public void setValue(AfonProperty property, String value) {
        loadLazily();
        if ( properties.containsKey(property.getName()) ){
            logger.info("Overriding property {} to {}", property.getName(), value);
        } else {
            logger.info("Setting property {} to {}", property.getName(), value);
        }
        this.properties.setProperty(property.getName(), value);
    }

    private void loadLazily() {
        if ( properties.isEmpty() ){
            // lazy loading and only once
            loadProperties();
        }
    }

    private void loadProperties() {
        types.forEach(type -> loadProperties(type));
    }

    private void loadProperties(String type) {
        try {
            loadPropertiesFromDir(type, configDir);
        } catch (IOException e) {
            logger.warn("Failed to load {} properties files in {}", type, configDir);
        }
    }

    private void loadPropertiesFromDir(String type, String dir) throws IOException {
        logger.info("Loading {} properties from {}", type, dir);
        for (String file : propertyFiles(type)) {
            String filename = String.format("%s/%s", dir, file);
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
        for (String key : AfonProperty.getAllNames()) {
            if (System.getProperty(key) != null) {
                logger.debug("Using the system variable to define property '{}'", key);
                properties.put(key, System.getProperty(key));
            }
        }
    }

    /**
     * Lists the {@code afon.properties} and {@code secret.properties} and its cloud-specific file(s).
     * {@link ConfigUtils#cloudPropertyValue()} selects a single cloud; otherwise every cloud's file
     * is listed, since some deployments (e.g. cross-cloud federation) need more than one cloud's
     * properties in the same run.
     */
    private List<String> propertyFiles(String type) {
        List<String> files = new ArrayList<>();
        // first add common properties
        files.add(fileName(type, null));
        // then add cloud properties
        if (cloudPropertySet()) {
            files.add(fileName(type, cloudPropertyValue()));
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
