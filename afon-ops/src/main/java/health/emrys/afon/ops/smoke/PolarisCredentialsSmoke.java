package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.config.PolarisCredentials;

import static health.emrys.afon.config.AfonCloud.*;

public class PolarisCredentialsSmoke {

    public void smokeTest(PolarisCredentials credentials) {
        System.out.printf("Access token for %s: %s", credentials.getCloud(), credentials.getAccessToken());
    }

    public static void main(String[] args) {
        var app = new PolarisCredentialsSmoke();
        var properties = new OpsPropertyProvider();
        app.smokeTest(new PolarisCredentials(AWS, properties));
        app.smokeTest(new PolarisCredentials(AZURE, properties));
        app.smokeTest(new PolarisCredentials(GCP, properties));
    }

}