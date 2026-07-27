package health.emrys.afon.polaris.comms;

import health.emrys.afon.config.AfonPropertiesLoader;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class AfonPolarisClientFactory {

    public static AfonPolarisClientFactory clientFactory() {
        return new AfonPolarisClientFactory();
    }

    private AfonPolarisClientFactory() {}

    public AfonPolarisClient getPolarisClient(Properties properties) {
        return new KeyCloakPolarisClient(properties);
    }

    public AfonPolarisClient getPolarisClient(String polarisHost, Properties properties) {
        Properties props = new Properties();
        props.putAll(properties);
        props.setProperty(POLARIS_ENDPOINT.getPropertyName(), polarisHost);
        return new KeyCloakPolarisClient(polarisHost, properties);
    }

    public AfonPolarisClient getPolarisClient(Properties properties, String protocol) {
        return new KeyCloakPolarisClient(properties, protocol);
    }

    public AfonPolarisClient getPolarisClient(String endpoint, String clientId, String clientSecret) {
        Properties props = new AfonPropertiesLoader().getProperties();
        props.setProperty(POLARIS_ENDPOINT.getPropertyName(), endpoint);
        props.setProperty(POLARIS_ID.getPropertyName(), clientId);
        props.setProperty(POLARIS_SECRET.getPropertyName(), clientSecret);
        return new KeyCloakPolarisClient(props);
    }

    public AfonPolarisClient getPolarisClient(String endpoint, String clientId, String clientSecret, String kcHost) {
        Properties props = new AfonPropertiesLoader().getProperties();
        props.setProperty(POLARIS_ENDPOINT.getPropertyName(), endpoint);
        props.setProperty(POLARIS_ID.getPropertyName(), clientId);
        props.setProperty(POLARIS_SECRET.getPropertyName(), clientSecret);
        props.setProperty(KC_HOSTNAME.getPropertyName(), kcHost);
        return new KeyCloakPolarisClient(props);
    }

}
