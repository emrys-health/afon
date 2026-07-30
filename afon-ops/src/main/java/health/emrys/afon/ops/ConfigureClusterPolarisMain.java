package health.emrys.afon.ops;

import health.emrys.afon.cluster.ClusterPolarisConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigureClusterPolarisMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureClusterPolarisMain.class);

    public static void main(String[] args) {
        try {
            try (ClusterPolarisConfiguration configuration = new ClusterPolarisConfiguration()) {
                configuration.configure(args);
            }
        } catch (Exception e) {
            logger.error("Error configuring cluster polaris", e);
        }
    }

}