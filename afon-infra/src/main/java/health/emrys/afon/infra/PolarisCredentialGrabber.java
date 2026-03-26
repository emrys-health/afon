package health.emrys.afon.infra;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.slf4j.Logger;

import java.util.Properties;

@Deprecated()
public class PolarisCredentialGrabber {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisCredentialGrabber.class);

    public static void main(String[] args) {
        try {
            var          clusterConfig     = new ClusterPolarisConfiguration();
            ClusterState clusterState      = clusterConfig.getClusterState();
            Properties   clusterProperties = AfonPropertiesLoader.add(clusterState.getCredentials(), clusterConfig.getClusterProperties());
            var          client            = new AfonPolarisClient(clusterProperties, "https");
            logger.info("Polaris credential: {}", clusterState.getCredentials());
            logger.info("Polaris access key: {}", client.getAccessToken());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final LogParser logParser;

    public PolarisCredentialGrabber() {
        logParser = new LogParser();
    }

    public String getCredentials(String logs) {
        return logParser.searchForTextAfter("realm: POLARIS root principal credentials: ", logs);
    }


}
