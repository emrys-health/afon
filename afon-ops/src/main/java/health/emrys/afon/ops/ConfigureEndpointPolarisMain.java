package health.emrys.afon.ops;

import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigureEndpointPolarisMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureEndpointPolarisMain.class);

    public static void main(String[] args) {
        try {
            try (EndpointPolarisConfiguration configuration = new EndpointPolarisConfiguration()) {
                configuration.configure(args);
            }
        } catch (Exception e) {
            logger.error("Error configuring endpoint polaris", e);
        }
    }

}