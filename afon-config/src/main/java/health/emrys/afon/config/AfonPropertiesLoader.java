package health.emrys.afon.config;

import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;
import static org.slf4j.LoggerFactory.getLogger;

public class AfonPropertiesLoader {

    private static final Logger logger = getLogger(AfonPropertiesLoader.class);
    private static final List<String> PROPERTY_FILES = List.of(PROJECT_PROPERTIES_FILE, ENV_PROPERTIES_FILE);

    private final Properties properties;

    public static Properties add(String accessToken, Properties properties) {
        String[] creds = accessToken.split(":");
        if (creds.length != 2) {
            logger.error("Incorrect access token {}. The token should have a ':' delimiting ID from Secret", accessToken);
        }
        properties.setProperty(POLARIS_ID.getPropertyName(), creds[0]);
        properties.setProperty(POLARIS_SECRET.getPropertyName(), creds[1]);
        return properties;
    }

    public AfonPropertiesLoader(String overrideFile) throws IOException {
        this();
        logger.info("Loading properties from {}", overrideFile);
        FileInputStream fis = new FileInputStream(overrideFile);
        properties.load(fis);
        fis.close();
    }

    public AfonPropertiesLoader() {
        properties = new Properties();
        try {
            // are we running as a build?
            readProperties("../");
        } catch (IOException e) {
            logger.warn("Could not read properties files in parent directory");
            try {
                // ...or in the IDE?
                readProperties("");
            } catch (IOException ex) {
                logger.warn("Could not find propery files");
                throw new RuntimeException(ex); // urgh, the properties file is not there
            }
        }
    }

    private void readProperties(String prepend) throws IOException {
        for (String file : PROPERTY_FILES) {
            Properties props = new Properties();
            String     filename  = prepend + file;
            logger.info("Reading properties file {}", filename);
            props.load(new FileInputStream(filename));
            logger.info("{} has {} items", filename, props.size());
            properties.putAll(props);
        }
    }


    public Properties getProperties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }
}
