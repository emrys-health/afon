package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;

import static health.emrys.afon.config.AfonProperty.*;

public class PolarisRestClientFactory {

    public static PolarisRestClientFactory clientFactory() {
        return new PolarisRestClientFactory();
    }

    private PolarisRestClientFactory() {}

    public PolarisRestClient createClient(AfonPropertyProvider properties) {
        return new KeyCloakPolarisRestClient(properties);
    }

    public PolarisRestClient createClient(String polarisHost, AfonPropertyProvider properties) {
        properties.setValue(POLARIS_ENDPOINT, polarisHost);
        return new KeyCloakPolarisRestClient(properties);
    }

    public PolarisRestClient createClient(AfonPropertyProvider properties, String protocol) {
        return new KeyCloakPolarisRestClient(properties, protocol);
    }

    public PolarisRestClient createClient(String endpoint, String clientId, String clientSecret) {
        AfonPropertyProvider properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, endpoint);
        properties.setValue(POLARIS_ID, clientId);
        properties.setValue(POLARIS_SECRET, clientSecret);
        return new KeyCloakPolarisRestClient(properties);
    }

    public PolarisRestClient createClient(String endpoint, String clientId, String clientSecret, String kcHost) {
        AfonPropertyProvider properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, endpoint);
        properties.setValue(POLARIS_ID, clientId);
        properties.setValue(POLARIS_SECRET, clientSecret);
        properties.setValue(KC_HOSTNAME, kcHost);
        return new KeyCloakPolarisRestClient(properties);
    }

}
