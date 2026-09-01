package health.emrys.afon.ops;

import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given an endpoint retrieve from property {@link AfonProperty#POLARIS_ENDPOINT}, the
 * {@link EndpointPolarisConfiguration} allows the configuration of the Polaris catalogs for that endpoint.
 * The property can be defined in file properties, or overridden via system property "POLARIS_ENDPOINT".
 *
 * A usage example:
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
        try {
            new EndpointPolarisConfiguration(properties).configure(args);
        } catch (Exception e) {
            logger.error("Failed to configure Polaris catalogs", e);
        }
    }

}