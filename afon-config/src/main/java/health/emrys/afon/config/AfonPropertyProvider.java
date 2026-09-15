package health.emrys.afon.config;

import java.util.Properties;

/**
 * An {@link AfonPropertyProvider} provides access to configured properties.
 *
 * Implementations include {@link FilePropertyProvider}, {@link ConstantsPropertyProvider},
 * {@link DefaultingPropertyProvider}, {@link DefaultPropertyProvider} and {@link ImmutablePropertyProvider}.
 */
public interface AfonPropertyProvider {

    Properties getProperties();

    Properties getSecretProperties();

    boolean hasProperty(AfonProperty property);

    String getValue(AfonProperty property);

    String getValue(AfonProperty property, String defaultValue);

    void setValue(AfonProperty property, String value);

    /**
     * Reads a per-catalog property keyed by convention as {@code <propertyName>_<catalogId>}, eg
     * {@code getCatalogProperty("AWS_BUCKET_NAME", "aws_external")} reads {@code AWS_BUCKET_NAME_aws_external}.
     * These properties are keyed by whatever catalog ids are configured at runtime (see
     * {@link AfonCatalogs}), so - unlike every other property here - they aren't declared as
     * {@link AfonProperty} constants and are read directly off {@link #getProperties()}.
     */
    default String getCatalogProperty(String propertyName, String catalogId) {
        return getProperties().getProperty(propertyName + "_" + catalogId);
    }

}
