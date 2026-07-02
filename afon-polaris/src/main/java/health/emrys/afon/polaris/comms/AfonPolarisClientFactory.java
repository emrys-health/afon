package health.emrys.afon.polaris.comms;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;
import static java.lang.String.format;

public class AfonPolarisClientFactory {

    public static AfonPolarisClientFactory clientFactory() {
        return new AfonPolarisClientFactory();
    }

    private AfonPolarisClientFactory() {}

    public AfonPolarisClient getPolarisClient(Properties properties) {
        return new AfonPolarisClient(properties, "http");
    }

    public AfonPolarisClient getPolarisClient(String httpsHost, Properties properties) {
        return new AfonPolarisClient(format("https://%s", httpsHost),
             POLARIS_ID.getValueFrom(properties),
             POLARIS_SECRET.getValueFrom(properties));
    }

    public AfonPolarisClient getPolarisClient(Properties properties, String protocol) {
        return new AfonPolarisClient(format("%s://%s", protocol, POLARIS_ENDPOINT.getValueFrom(properties)),
             POLARIS_ID.getValueFrom(properties),
             POLARIS_SECRET.getValueFrom(properties));
    }

    public AfonPolarisClient getPolarisClient(String endpoint, String clientId, String clientSecret) {
        return new AfonPolarisClient(endpoint, clientId, clientSecret);
    }

}
