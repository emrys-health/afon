package health.emrys.afon.config;

import org.slf4j.Logger;

import java.util.Properties;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class Redacter {

    private static final Logger logger = getLogger(Redacter.class);
    public static final String NON_REDACTABLE_WORDS = "afon|emrys|will";

    /**
     * Redacts secrets in the content from a given source, using the {@link FilePropertyProvider#getSecretProperties()}.
     *
     * @param content
     * @param source
     * @return the redacted content
     */
    public static String redactSecrets(String content, String source) {
        return redactSecrets(content, source, new FilePropertyProvider().getSecretProperties());
    }

    /**
     * Redacts secrets in the content from a given source, using the properties provided.
     *
     * @param content
     * @param source
     * @param properties
     * @return the redacted content
     */
    public static String redactSecrets(String content, String source, Properties properties) {
        logger.info("Redacting content from source " + source);
        for (String key : properties.stringPropertyNames()){
            String  original = content;
            String value = properties.getProperty(key);
            if ( redactable(value) ) {
                content = content.replace(value, format("<%s>", key.toLowerCase()));
            }
            if (!original.equals(content)) {
                logger.info("Redacted in {} property value {} of key {}", source, value, key);
            }
        }
        return content;
    }

    /**
     * Do not redact value if it is a common word
     * @param value
     * @return a flag to redact or not the value
     */
    private static boolean redactable(String value) {
        return !value.matches(NON_REDACTABLE_WORDS);
    }

}
