package health.emrys.afon.config;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static java.util.Set.copyOf;

public class AfonProperty {

    private static final Set<String> ALL_NAMES = new HashSet<>();

    private final String name;

    AfonProperty(String name) {
        this.name = name;
        if (ALL_NAMES.contains(name)) {
            throw new IllegalArgumentException("Property " + name + " is already defined");
        }
        ALL_NAMES.add(name);
    }

    public static Set<String> getAllNames() {
        return copyOf(ALL_NAMES);
    }

    public String getValueFrom(Properties properties) {
        String value = properties.getProperty(name);
        if (value == null) {
            throw new IllegalArgumentException("Property " + name + " not found");
        }
        return value;
    }

    /**
     * As {@link #getValueFrom(Properties)} but falls back to {@code defaultValue} when the property is
     * absent, instead of throwing. Use for optional properties that have a sensible default (e.g. the
     * KeyCloak scheme, which defaults to https for real deployments but is overridden to http in tests).
     */
    public String getValueFrom(Properties properties, String defaultValue) {
        return properties.getProperty(name, defaultValue);
    }

    public String getPropertyName() {
        return name;
    }
}
