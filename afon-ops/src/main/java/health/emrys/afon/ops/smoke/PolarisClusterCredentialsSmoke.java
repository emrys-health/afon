package health.emrys.afon.ops.smoke;

import health.emrys.afon.cluster.ClusterCredentials;
import health.emrys.afon.cluster.PolarisClusterCredentials;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;

import static health.emrys.afon.config.AfonCloud.*;

public class PolarisClusterCredentialsSmoke {

    public void smokeTest(AfonCloud cloud, ClusterCredentials credentials) {
        System.out.printf("Access token for %s: %s", cloud, credentials.getAccessToken());
    }

    public static void main(String[] args) {
        var app = new PolarisClusterCredentialsSmoke();
        var properties = new FilePropertyProvider();
        app.smokeTest(AWS, new PolarisClusterCredentials(AWS, properties));
        app.smokeTest(AZURE, new PolarisClusterCredentials(AZURE, properties));
        app.smokeTest(GCP, new PolarisClusterCredentials(GCP, properties));
    }

}