package health.emrys.afon.polaris.comms;

import java.util.Properties;

public class AfonPolarisClientFactory {

    public static AfonPolarisClientFactory clientFactory() {
        return new AfonPolarisClientFactory();
    }

    private AfonPolarisClientFactory() {}

    public AfonPolarisClient getPolarisClient(Properties properties) {
        return new AfonPolarisClient(properties);
    }

    public AfonPolarisClient getPolarisClient(String httpsHost, Properties properties) {
        return new AfonPolarisClient(httpsHost, properties);
    }

    public AfonPolarisClient getPolarisClient(Properties properties, String protocol) {
        return new AfonPolarisClient(properties, protocol);
    }

    public AfonPolarisClient getPolarisClient(String endpoint, String clientId, String clientSecret) {
        return new AfonPolarisClient(endpoint, clientId, clientSecret);
    }

}
