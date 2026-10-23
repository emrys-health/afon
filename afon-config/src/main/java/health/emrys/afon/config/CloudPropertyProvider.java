package health.emrys.afon.config;

import java.util.Properties;

/**
 * The properties as seen by a flow calling the Polaris of a cloud: {@code OIDC_DOMAIN_NAME} and {@code OIDC_ISSUER_URL} are those of
 * the KeyCloak of that cloud, see {@link AfonPropertyProvider#getOidcDomainName(AfonCloud)}, whatever the cloud-neutral ones, since each
 * cloud may have its own KeyCloak, with its own users and tokens.  The clients, which read {@code OIDC_DOMAIN_NAME}, then
 * authenticate with the KeyCloak of the Polaris they call.  Everything else is read from, and written to, the decorated provider.
 */
public record CloudPropertyProvider(AfonPropertyProvider properties, AfonCloud cloud) implements AfonPropertyProvider {

    @Override
    public Properties getProperties() {
        Properties merged = new Properties();
        merged.putAll(properties.getProperties());
        merged.setProperty(AfonProperty.OIDC_DOMAIN_NAME.getName(), properties.getOidcDomainName(cloud));
        merged.setProperty(AfonProperty.OIDC_ISSUER_URL.getName(), properties.getOidcIssuerUrl(cloud));
        return merged;
    }

    @Override
    public Properties getSecretProperties() {
        return properties.getSecretProperties();
    }

    @Override
    public boolean hasProperty(AfonProperty property) {
        return properties.hasProperty(property);
    }

    @Override
    public String getValue(AfonProperty property) {
        return switch (property) {
            case OIDC_DOMAIN_NAME -> properties.getOidcDomainName(cloud);
            case OIDC_ISSUER_URL -> properties.getOidcIssuerUrl(cloud);
            default -> properties.getValue(property);
        };
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        return hasProperty(property) ? getValue(property) : defaultValue;
    }

    @Override
    public void setValue(AfonProperty property, String value) {
        properties.setValue(property, value);
    }

    @Override
    public AfonProperty getOidcDomainProperty(AfonCloud other) {
        return properties.getOidcDomainProperty(other);
    }

    @Override
    public String getOidcDomainName(AfonCloud other) {
        return properties.getOidcDomainName(other);
    }

    @Override
    public String getOidcIssuerUrl(AfonCloud other) {
        return properties.getOidcIssuerUrl(other);
    }
}
