package health.emrys.afon.config;

import java.util.Properties;

/**
 * An {@link AfonPropertyProvider} provides access to configured properties.
 *
 * Implementations include {@link FilePropertyProvider} and {@link GivenPropertyProvider}.
 */
public interface AfonPropertyProvider {
    Properties getProperties();

    Properties getSecretProperties();

    String getValue(AfonProperty property);

    String getValue(AfonProperty property, String defaultValue);
}
