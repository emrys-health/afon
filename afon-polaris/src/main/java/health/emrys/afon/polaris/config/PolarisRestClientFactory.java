package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.keycloak.KeyCloakPolarisRestClient;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

public class PolarisRestClientFactory {

    public static PolarisRestClientFactory clientFactory() {
        return new PolarisRestClientFactory();
    }

    private PolarisRestClientFactory() {}

    public PolarisRestClient createClient(AfonPropertyProvider properties) {
        return new KeyCloakPolarisRestClient(properties);
    }
    public PolarisRestClient createClient(AfonPropertyProvider properties, String polarisEndpoint) {
        properties.setValue(POLARIS_ENDPOINT, polarisEndpoint);
        return new KeyCloakPolarisRestClient(properties);
    }

}
