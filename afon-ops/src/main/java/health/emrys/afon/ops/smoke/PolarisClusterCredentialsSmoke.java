package health.emrys.afon.ops.smoke;

import health.emrys.afon.cluster.ClusterCredentials;
import health.emrys.afon.cluster.PolarisClusterCredentials;
import health.emrys.afon.config.AfonProperties.AfonCloud;
import org.slf4j.Logger;

import static health.emrys.afon.config.AfonProperties.AfonCloud.*;

public class PolarisClusterCredentialsSmoke {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisClusterCredentialsSmoke.class);

    public PolarisClusterCredentialsSmoke() {
    }

    public void createCredentials(AfonCloud cloud) {
        logger.info("Creating cluster credentials for "+ cloud);
        ClusterCredentials credentials = PolarisClusterCredentials.createClusterCredentials(cloud);
        logger.info("Access token for {}: {}", cloud, credentials.getAccessToken());
    }

    public void main(String[] args) {
        var app = new PolarisClusterCredentialsSmoke();
        app.createCredentials(AWS);
        app.createCredentials(AZURE);
        app.createCredentials(GCP);
    }

}