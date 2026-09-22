package health.emrys.afon.config;

import org.slf4j.Logger;

import java.util.Properties;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class Redacter {

    private static final Logger logger = getLogger(Redacter.class);
    public static final String NON_REDACTABLE_WORDS = "afon|emrys|will";

    /**
     * Redacts secrets in the content from a given source, using the secret properties provided.
     *
     * @param content the content to redact
     * @param source the source of the redaction
     * @param secrets the Properties containing the secrets to redact.
     * @return the redacted content
     */
    public static String redactSecrets(String content, String source, Properties secrets) {
        logger.info("Redacting content from source " + source);
        for (String key : secrets.stringPropertyNames()){
            String  original = content;
            String value = secrets.getProperty(key);
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
