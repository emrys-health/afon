package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.KeyCloakPolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClient;

public class PolarisAccessTokenSmoke {

    public void smokeTest(PolarisRestClient client) {
        try {
            System.out.printf("Polaris access token: %s",client.accessToken());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        var app = new PolarisAccessTokenSmoke();
        var properties = new FilePropertyProvider();
        app.smokeTest(new KeyCloakPolarisRestClient(properties));
    }

}
