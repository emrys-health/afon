package health.emrys.afon.config;

import java.util.Properties;

/**
 * An implementation of {@link AfonPropertyProvider} that provides the given already-loaded
 * (and possibly overridden) {@link Properties}
 */
public record GivenPropertyProvider(Properties properties) implements AfonPropertyProvider {
    @Override
    public Properties getProperties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    @Override
    public Properties getProperties(String overrideFile) {
        return getProperties();
    }

    @Override
    public Properties getSecretProperties() {
        return getProperties();
    }

    @Override
    public String getValue(AfonProperty property) {
        return property.getValueFrom(properties);
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        return property.getValueFrom(properties, defaultValue);
    }
}
