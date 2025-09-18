package health.emrys.afon.config;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static java.util.Set.copyOf;

public class AfonProperty {

    private static final Set<String> ALL_PROPERTIES = new HashSet<>();

    private final String propertyName;

    AfonProperty(String propertyName) {
        this.propertyName = propertyName;
        if (ALL_PROPERTIES.contains(propertyName)) {
            throw new IllegalArgumentException("Property " + propertyName + " is already defined");
        }
        ALL_PROPERTIES.add(propertyName);
    }

    public static Set<String> getAllProperties() {
        return copyOf(ALL_PROPERTIES);
    }

    public String getValueFrom(Properties properties) {
        String value = properties.getProperty(propertyName);
        if (value == null) {
            throw new IllegalArgumentException("Property " + propertyName + " not found");
        }
        return value;
    }
}
