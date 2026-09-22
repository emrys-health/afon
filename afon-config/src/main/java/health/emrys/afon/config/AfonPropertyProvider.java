package health.emrys.afon.config;

import java.util.Properties;

/**
 * An {@link AfonPropertyProvider} provides access to configured properties.
 *
 * Implementations include {@link FilePropertyProvider} and {@link ImmutablePropertyProvider}.
 */
public interface AfonPropertyProvider {

    Properties getProperties();

    Properties getSecretProperties();

    boolean hasProperty(AfonProperty property);

    String getValue(AfonProperty property);

    String getValue(AfonProperty property, String defaultValue);

    void setValue(AfonProperty property, String value);

}
