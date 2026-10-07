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
     * Reads the cloud's own property of a logical name, by the convention {@code <CLOUD>_<NAME>}, see
     * {@link AfonProperty#forCloud(AfonCloud, String)}, eg {@code getCloudValue(AWS, "POLARIS_DOMAIN_NAME")} reads
     * {@code AWS_POLARIS_DOMAIN_NAME}.
     *
     * @throws IllegalArgumentException if the cloud has no property of that name, or it is not set
     */
    default String getCloudValue(AfonCloud cloud, String logicalName) {
        return getValue(AfonProperty.forCloud(cloud, logicalName).orElseThrow(() -> new IllegalArgumentException(String.format(
            "No property %s_%s for the cloud %s", cloud.toString().toUpperCase(), logicalName, cloud))));
    }

    /**
     * As {@link #getCloudValue(AfonCloud, String)} but falls back to {@code defaultValue} when the cloud has no
     * property of that name, or it is not set, as for properties that only some clouds have, or are optional.
     */
    default String getCloudValue(AfonCloud cloud, String logicalName, String defaultValue) {
        return AfonProperty.forCloud(cloud, logicalName)
                           .map(property -> getValue(property, defaultValue))
                           .orElse(defaultValue);
    }

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
