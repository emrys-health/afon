package health.emrys.afon.config;

import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static health.emrys.afon.config.AfonProperties.*;
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

    public static Properties add(String credentials, Properties properties) {
        String[] creds = credentials.split(":");
        if (creds.length != 2) {
            logger.error("Incorrect access token {}. The token should have a ':' delimiting ID from Secret", credentials);
        }
        properties.setProperty(POLARIS_ID.getName(), creds[0]);
        properties.setProperty(POLARIS_SECRET.getName(), creds[1]);
        return properties;
    }

    public AfonPropertiesLoader(String overrideFile) throws IOException {
        this();
        logger.debug("Loading properties from {}", overrideFile);
        FileInputStream fis = new FileInputStream(overrideFile);
        properties.load(fis);
        fis.close();
    }

    public AfonPropertiesLoader() {
        properties = new Properties();
        try {
            // are we running as a build?
            readProperties("../config");
        } catch (IOException e) {
            logger.warn("Could not read properties files in parent directory. Trying in this...");
            try {
                // ...or in the IDE?
                readProperties("config");
            } catch (IOException ex) {
                logger.error("Could not find properties files");
                throw new RuntimeException(ex); // urgh, the properties file not found
            }
        }
    }

    private void readProperties(String prepend) throws IOException {
        for (String file : propertyFiles()) {
            Properties props    = new Properties();
            String     filename = prepend + "/" + file;
            logger.info("Reading properties file {}", filename);
            props.load(new FileInputStream(filename));
            logger.info("{} has {} items", filename, props.size());
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
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }
}
