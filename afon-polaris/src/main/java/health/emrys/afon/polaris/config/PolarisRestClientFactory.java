package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.keycloak.KeyCloakPolarisRestClient;

public class PolarisRestClientFactory {

    public static PolarisRestClientFactory clientFactory() {
        return new PolarisRestClientFactory();
    }

    private PolarisRestClientFactory() {}

    public PolarisRestClient createClient(AfonPropertyProvider properties) {
        return new KeyCloakPolarisRestClient(properties);
    }

}
