package health.emrys.afon.config;

import org.slf4j.Logger;

import java.util.Properties;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class Redacter {

    private static final Logger logger = getLogger(Redacter.class);

    public static String redactSecrets(String content, String source) {
        Properties secretProperties   = new FilePropertyProvider().getSecretProperties();
        for (String key : secretProperties.stringPropertyNames()){
            String  original = content;
            String value = secretProperties.getProperty(key);
            if ( redactable(value) ) {
                content = content.replace(value, format("<%s>", key.toLowerCase()));
            }
            if (!original.equals(content)) {
                logger.info("Redacted in {} property value {} of key {}", source, value, key);
            }
        }
        return content;
    }

    private static boolean redactable(String value) {
        return !value.matches("afon|emrys|will");
    }


}
