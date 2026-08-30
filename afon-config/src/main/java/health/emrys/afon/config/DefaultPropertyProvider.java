package health.emrys.afon.config;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * A delegator implementation of {@link AfonPropertyProvider} that combines a {@link FilePropertyProvider}
 * with a {@link ConstantsPropertyProvider} built from the given classes, via {@link DefaultingPropertyProvider}.
 *
 * Equivalent to {@code new DefaultingPropertyProvider(new FilePropertyProvider(), new ConstantsPropertyProvider(classes))}.
 */
public class DefaultPropertyProvider implements AfonPropertyProvider {

    private final AfonPropertyProvider delegate;

    public DefaultPropertyProvider(Class<?>... classes) {
        this(Arrays.asList(classes));
    }

    public DefaultPropertyProvider(List<Class<?>> classes) {
        this.delegate = new DefaultingPropertyProvider(new FilePropertyProvider(), new ConstantsPropertyProvider(classes));
    }

    @Override
    public Properties getProperties() {
        return delegate.getProperties();
    }

    @Override
    public Properties getSecretProperties() {
        return delegate.getSecretProperties();
    }

    @Override
    public boolean hasProperty(AfonProperty property) {
        return delegate.hasProperty(property);
    }

    @Override
    public String getValue(AfonProperty property) {
        return delegate.getValue(property);
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        return delegate.getValue(property, defaultValue);
    }

    @Override
    public void setValue(AfonProperty property, String value) {
        delegate.setValue(property, value);
    }

}
