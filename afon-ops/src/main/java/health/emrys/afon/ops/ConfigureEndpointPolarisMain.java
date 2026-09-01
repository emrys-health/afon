package health.emrys.afon.ops;

import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

public class ConfigureEndpointPolarisMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureEndpointPolarisMain.class);

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        String domainName = System.getProperty("POLARIS_ENDPOINT");
        if (domainName != null) {
            properties.setValue(POLARIS_ENDPOINT, domainName);
        } else {
            logger.error("POLARIS_ENDPOINT is not defined.");
        }
        try {
            new EndpointPolarisConfiguration(properties).configure(args);
        } catch (Exception e) {
            logger.error("Error configuring endpoint Polaris", e);
        }
    }

}