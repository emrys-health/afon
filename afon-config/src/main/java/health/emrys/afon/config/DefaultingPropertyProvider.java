package health.emrys.afon.config;

import java.util.Properties;

/**
 * A decorator implementation of {@link AfonPropertyProvider} that falls back to a {@code defaults} provider
 * for any property not declared in the wrapped {@code provider}, e.g. {@code
 * new DefaultingPropertyProvider(new FilePropertyProvider(), new ConstantsPropertyProvider(MyConstants.class))}.
 *
 * {@code provider} always takes precedence over {@code defaults}. {@link #setValue} and
 * {@link #getSecretProperties} are delegated to {@code provider} only, since defaults are not meant to be
 * mutated and are not secrets.
 */
public record DefaultingPropertyProvider(AfonPropertyProvider provider,
                                          AfonPropertyProvider defaults) implements AfonPropertyProvider {

    @Override
    public Properties getProperties() {
        Properties merged = new Properties();
        // the order in which they are merged is important
        // provider will override defaults
        merged.putAll(defaults.getProperties());
        merged.putAll(provider.getProperties());
        return merged;
    }

    @Override
    public Properties getSecretProperties() {
        return provider.getSecretProperties();
    }

    @Override
    public boolean hasProperty(AfonProperty property) {
        return provider.hasProperty(property) || defaults.hasProperty(property);
    }

    @Override
    public String getValue(AfonProperty property) {
        return property.getValueFrom(getProperties());
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        return property.getValueFrom(getProperties(), defaultValue);
    }

    @Override
    public void setValue(AfonProperty property, String value) {
        provider.setValue(property, value);
    }

}
