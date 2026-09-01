package health.emrys.afon.ops;

import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.cluster.PolarisConfiguration;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * Given an endpoint retrieve from property {@link AfonProperty#POLARIS_ENDPOINT}, the
 * {@link EndpointPolarisConfiguration} allows the configuration of the Polaris catalogs for that endpoint.
 * The property can be defined in file properties, or overridden via system property "POLARIS_ENDPOINT".
 *
 * An example of usage of this {@link PolarisConfiguration} is:
 * <pre>
 ./afon.sh -a exec --exec-class ops.ConfigureEndpointPolarisMain
 --exec-args "AZURE_EXTERNAL POLARIS_ID:POLARIS_SECRET"
 --exec-envs "POLARIS_ENDPOINT=emryspolarisazure.click"
 * </pre>
 */
public class ConfigureEndpointPolarisMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureEndpointPolarisMain.class);

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        String domainName = System.getProperty("POLARIS_ENDPOINT");
        if (domainName != null) {
            properties.setValue(POLARIS_ENDPOINT, domainName);
        } else {
            logger.warn("POLARIS_ENDPOINT is not defined. Defaulting to what is already defined in file properties.");
        }
        try {
            new EndpointPolarisConfiguration(properties).configure(args);
        } catch (Exception e) {
            logger.error("Error configuring endpoint Polaris", e);
        }
    }

}