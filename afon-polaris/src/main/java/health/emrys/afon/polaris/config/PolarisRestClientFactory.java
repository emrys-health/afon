package health.emrys.afon.polaris.config;

import health.emrys.afon.config.FilePropertyProvider;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.*;

public class PolarisRestClientFactory {

    public static PolarisRestClientFactory clientFactory() {
        return new PolarisRestClientFactory();
    }

    private PolarisRestClientFactory() {}

    public PolarisRestClient createClient(Properties properties) {
        return new KeyCloakPolarisRestClient(properties);
    }

    public PolarisRestClient createClient(String polarisHost, Properties properties) {
        Properties props = new Properties();
        props.putAll(properties);
        props.setProperty(POLARIS_ENDPOINT.getName(), polarisHost);
        return new KeyCloakPolarisRestClient(polarisHost, properties);
    }

    public PolarisRestClient createClient(Properties properties, String protocol) {
        return new KeyCloakPolarisRestClient(properties, protocol);
    }

    public PolarisRestClient createClient(String endpoint, String clientId, String clientSecret) {
        Properties properties = new FilePropertyProvider().getProperties();
        properties.setProperty(POLARIS_ENDPOINT.getName(), endpoint);
        properties.setProperty(POLARIS_ID.getName(), clientId);
        properties.setProperty(POLARIS_SECRET.getName(), clientSecret);
        return new KeyCloakPolarisRestClient(properties);
    }

    public PolarisRestClient createClient(String endpoint, String clientId, String clientSecret, String kcHost) {
        Properties properties = new FilePropertyProvider().getProperties();
        properties.setProperty(POLARIS_ENDPOINT.getName(), endpoint);
        properties.setProperty(POLARIS_ID.getName(), clientId);
        properties.setProperty(POLARIS_SECRET.getName(), clientSecret);
        properties.setProperty(KC_HOSTNAME.getName(), kcHost);
        return new KeyCloakPolarisRestClient(properties);
    }

}
