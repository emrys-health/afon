package health.emrys.afon.config;

import java.net.URI;
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
     * Reads the cloud's own property of a cloud-agnostic name, by the convention {@code <CLOUD>_<NAME>}, see
     * {@link AfonCloudProperty#forCloud(AfonCloud)}, eg {@code getCloudValue(AWS, POLARIS_DOMAIN_NAME)} reads
     * {@code AWS_POLARIS_DOMAIN_NAME}.
     *
     * @throws IllegalArgumentException if the cloud has no property of that name, or it is not set
     */
    default String getCloudValue(AfonCloud cloud, AfonCloudProperty cloudProperty) {
        return getValue(cloudProperty.forCloud(cloud).orElseThrow(() -> new IllegalArgumentException(String.format(
            "No property %s_%s for the cloud %s", cloud.toString().toUpperCase(), cloudProperty.name(), cloud))));
    }

    /**
     * As {@link #getCloudValue(AfonCloud, AfonCloudProperty)} but falls back to {@code defaultValue} when the cloud has no
     * property of that name, or it is not set, as for properties that only some clouds have, or are optional.
     */
    default String getCloudValue(AfonCloud cloud, AfonCloudProperty cloudProperty, String defaultValue) {
        return cloudProperty.forCloud(cloud)
                           .map(property -> getValue(property, defaultValue))
                           .orElse(defaultValue);
    }

    /**
     * @return the property holding the domain of the cloud's KeyCloak: the cloud's own, eg {@code AWS_OIDC_DOMAIN_NAME}, if it is
     * set and not blank, else {@code OIDC_DOMAIN_NAME}.  The one place that holds this fallback.
     */
    default AfonProperty getOidcDomainProperty(AfonCloud cloud) {
        return AfonCloudProperty.OIDC_DOMAIN_NAME.forCloud(cloud)
                                                 .filter(property -> !getValue(property, "").isBlank())
                                                 .orElse(AfonProperty.OIDC_DOMAIN_NAME);
    }

    /**
     * @return the domain of the cloud's KeyCloak, see {@link #getOidcDomainProperty(AfonCloud)}
     */
    default String getOidcDomainName(AfonCloud cloud) {
        return getValue(getOidcDomainProperty(cloud));
    }

    /** In an issuer URL, stands for the OIDC domain name of the cloud, eg {@code https://[OIDC_DOMAIN_NAME]/realms/polaris}. */
    String OIDC_DOMAIN_PLACEHOLDER = "[OIDC_DOMAIN_NAME]";

    /**
     * @return the OIDC issuer URL of the cloud's KeyCloak: {@code OIDC_ISSUER_URL}, a template in which
     * {@value #OIDC_DOMAIN_PLACEHOLDER} is replaced by the {@link #getOidcDomainName(AfonCloud) domain} of the cloud
     * @throws IllegalStateException if the issuer is not at the domain of the cloud's KeyCloak, eg {@code OIDC_ISSUER_URL}
     *                               names the domain of another cloud's KeyCloak
     */
    default String getOidcIssuerUrl(AfonCloud cloud) {
        String domain = getOidcDomainName(cloud);
        String issuer = getValue(AfonProperty.OIDC_ISSUER_URL).trim().replace(OIDC_DOMAIN_PLACEHOLDER, domain);
        String host = URI.create(issuer).getHost();
        if (!domain.equalsIgnoreCase(host)) { // host names are case-insensitive
            throw new IllegalStateException(String.format(
                "The OIDC issuer %s of %s is at %s, not at its OIDC domain name %s: use %s in OIDC_ISSUER_URL",
                issuer, cloud, host, domain, OIDC_DOMAIN_PLACEHOLDER));
        }
        return issuer;
    }

    /**
     * @return these properties as seen by a flow calling the Polaris of the cloud, see {@link CloudPropertyProvider}: its
     * {@code OIDC_DOMAIN_NAME} and {@code OIDC_ISSUER_URL} are those of the cloud's KeyCloak
     */
    default AfonPropertyProvider forCloud(AfonCloud cloud) {
        return new CloudPropertyProvider(this, cloud);
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
