package health.emrys.afon.config;

import java.util.Properties;

/**
 * A record immutable implementation of {@link AfonPropertyProvider} that decorates the already-specified provider.
 */
public record ImmutablePropertyProvider(AfonPropertyProvider properties) implements AfonPropertyProvider {

    @Override
    public Properties getProperties() {
        return properties.getProperties();
    }

    @Override
    public Properties getSecretProperties() {
        return getProperties();
    }

    @Override
    public boolean hasProperty(AfonProperty property) {
        return properties.hasProperty(property);
    }

    @Override
    public String getValue(AfonProperty property) {
        return properties.getValue(property);
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        return properties.getValue(property, defaultValue);
    }

    @Override
    public void setValue(AfonProperty property, String value) {
        // no-op
    }
}
