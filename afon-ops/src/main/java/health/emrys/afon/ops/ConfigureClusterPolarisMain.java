package health.emrys.afon.ops;

import health.emrys.afon.cluster.ClusterPolarisConfiguration;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigureClusterPolarisMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureClusterPolarisMain.class);

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        try {
            new ClusterPolarisConfiguration(properties).configure(args);
        } catch (Exception e) {
            logger.error("Error configuring cluster Polaris", e);
        }
    }

}