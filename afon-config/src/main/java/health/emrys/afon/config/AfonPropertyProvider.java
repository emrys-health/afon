package health.emrys.afon.config;

import java.io.IOException;
import java.util.Properties;

/**
 * An {@link AfonPropertyProvider} allows the retrieval of properties based on the cloud configuration.
 */
public interface AfonPropertyProvider {
    Properties getProperties();

    Properties getProperties(String overrideFile) throws IOException;

    Properties getSecretProperties();
}
