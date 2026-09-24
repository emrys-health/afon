package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * {@link AfonCatalog} definitions live in {@code catalogs.properties}, with cloud-specific
 * definitions alongside it in {@code catalogs-<cloud>.properties} (e.g. {@code catalogs-aws.properties}).
 *
 * The properties files are loaded from the {@link #configDir}, which defaults to {@link ConfigUtils#configDir()}.
 *
 * When {@link ConfigUtils#cloudPropertySet()} is {@code false}, every cloud's files are loaded together,
 * since some deployments (e.g. cross-cloud federation) need more than one cloud's properties at the same time.
 * The cloud-specific files are optional: a missing one is skipped, so that a deployment in one cloud
 * need only provide the files of that cloud.
 *
 * Any property will be overridden if it is provided as system property, e.g. {@code -DCLUSTER_NAME=<new-name>}.
 *
 * A property value may reference another loaded property with {@code ${OTHER_KEY}}, e.g.
 * {@code CLUSTER_NAME=${ENV_NAME}-cluster}. References are resolved after all files are loaded
 * and system property overrides are applied, so an override of {@code ENV_NAME} also flows through.
 */
public class FilePropertyProvider implements AfonPropertyProvider {

    private static final Logger logger = getLogger(FilePropertyProvider.class);

    /**
     * Property types
     */
    private static final List<String> ALL_TYPES = List.of("afon", "secret", "catalogs");

    /**
     * Cloud types
     */
    private static final List<String> ALL_CLOUDS = List.of("aws", "azure", "gcp");

    /**
     * The interpolation placeholder pattern, to match {@code ${PROPERTY_NAME}}.
     */
    private static final Pattern INTERPOLATION_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");

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
        String name = property.getName();
        if ( properties.containsKey(name) ){
            logger.info("Overriding property '{}' with value '{}'", name, value);
        } else {
            logger.info("Setting property '{}' with value '{}'", name, value);
        }
        this.properties.setProperty(name, value);
    }

    private void loadLazily() {
        if ( properties.isEmpty() ){
            // lazy loading and only once
            loadProperties();
        }
    }

    private void loadProperties() {
        types.forEach(type -> loadProperties(type));
        overrideWithSystemProperties();
        interpolateProperties();
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
        String commonFile = fileForCloud(type, null);
        for (String file : propertyFiles(type)) {
            String filename = String.format("%s/%s", dir, file);
            if (!file.equals(commonFile) && !new File(filename).exists()) {
                // cloud-specific files are optional, so that a deployment in one cloud
                // does not depend on the properties of the other clouds
                logger.info("Skipping missing {} properties file {}", type, filename);
                continue;
            }
            Properties props = loadPropertiesFromFile(filename);
            properties.putAll(props);
            if (file.startsWith("secret")) {
                secretKeys.addAll(props.keySet().stream().map(x -> x.toString()).toList());
            }
        }
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
        for (String name : AfonProperty.getAllNames()) {
            String value = System.getProperty(name);
            if (value != null) {
                logger.info("Using the system property to override property '{}' with value '{}'", name, value);
                properties.put(name, value);
            }
        }
    }

    /**
     * Resolves {@code ${OTHER_NAME}} references in property values against the other loaded properties.
     */
    private void interpolateProperties() {
        for (String name : properties.stringPropertyNames()) {
            properties.setProperty(name, resolvePlaceholders(name, new LinkedHashSet<>()));
        }
    }

    private String resolvePlaceholders(String name, Set<String> seen) {
        String value = properties.getProperty(name);
        if (value == null || !value.contains("${")) {
            return value;
        }
        if (!seen.add(name)) {
            throw new IllegalStateException(
                    "Circular property reference: " + String.join(" -> ", seen) + " -> " + name);
        }
        Matcher matcher = INTERPOLATION_PATTERN.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String referencedKey = matcher.group(1);
            if (!properties.containsKey(referencedKey)) {
                throw new IllegalStateException(
                        "Property '" + name + "' references undefined property '" + referencedKey + "'");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(resolvePlaceholders(referencedKey, seen)));
        }
        matcher.appendTail(resolved);
        seen.remove(name);
        return resolved.toString();
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
        files.add(fileForCloud(type, null));
        // then add cloud properties
        if (cloudPropertySet()) {
            files.add(fileForCloud(type, cloudPropertyValue()));
        } else {
            ALL_CLOUDS.forEach(c -> files.add(fileForCloud(type, c)));
        }
        return files;
    }

    private static String fileForCloud(String type, String cloud) {
        if (cloud != null) {
            return type + "-" + cloud.toLowerCase() + ".properties";
        } else {
            return type + ".properties";
        }
    }

}
