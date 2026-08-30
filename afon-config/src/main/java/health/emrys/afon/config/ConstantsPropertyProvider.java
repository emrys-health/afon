package health.emrys.afon.config;

import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Implementation of {@link AfonPropertyProvider} that reads properties from the public static fields of a
 * set of classes.
 *
 * The field name becomes the property name and its value (via {@link String#valueOf}) becomes the property
 * value.
 *
 * Intended to supply default values for properties that are not otherwise declared, e.g. in the properties
 * files loaded by {@link FilePropertyProvider}.
 */
public class ConstantsPropertyProvider implements AfonPropertyProvider {

    private static final Logger logger = getLogger(ConstantsPropertyProvider.class);

    private final List<Class<?>> classes;
    private final Properties properties = new Properties();

    public ConstantsPropertyProvider(Class<?>... classes) {
        this(Arrays.asList(classes));
    }

    public ConstantsPropertyProvider(List<Class<?>> classes) {
        this.classes = classes;
    }

    @Override
    public Properties getProperties() {
        loadLazily();
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    @Override
    public Properties getSecretProperties() {
        return new Properties();
    }

    @Override
    public boolean hasProperty(AfonProperty property) {
        loadLazily();
        return properties.containsKey(property.getName());
    }

    @Override
    public String getValue(AfonProperty property) {
        loadLazily();
        return property.getValueFrom(properties);
    }

    @Override
    public String getValue(AfonProperty property, String defaultValue) {
        loadLazily();
        return property.getValueFrom(properties, defaultValue);
    }

    @Override
    public void setValue(AfonProperty property, String value) {
        loadLazily();
        this.properties.setProperty(property.getName(), value);
    }

    private void loadLazily() {
        if (properties.isEmpty()) {
            // lazy loading and only once
            loadProperties();
        }
    }

    private void loadProperties() {
        classes.forEach(this::loadProperties);
    }

    private void loadProperties(Class<?> clazz) {
        for (Field field : clazz.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                loadProperty(field);
            }
        }
    }

    private void loadProperty(Field field) {
        String name = field.getName();
        try {
            Object value = field.get(null);
            if (value != null && isAfonProperty(name)) {
                properties.setProperty(name, String.valueOf(value));
            }
        } catch (IllegalAccessException e) {
            logger.warn("Failed to read constant {}.{}", field.getDeclaringClass().getName(), name, e);
        }
    }

    private boolean isAfonProperty(String name) {
        try {
            return AfonProperty.valueOf(name) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

}
