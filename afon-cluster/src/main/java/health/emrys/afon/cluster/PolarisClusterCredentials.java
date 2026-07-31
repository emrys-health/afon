package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.AfonCloud;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;

import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AfonCloud.*;

public class PolarisClusterCredentials implements ClusterCredentials {

    private static final Properties properties = new AfonPropertiesLoader().getProperties();
    private static final Map<AfonCloud, String> POLARIS_HOST_FOR
        = Map.of(AWS, AfonProperties.AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties),
                 AZURE, AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(properties),
                 GCP, AfonProperties.GCP_DOMAIN_NAME.getValueFrom(properties));


    public static ClusterCredentials createClusterCredentials(AfonCloud cloud) {
        return new PolarisClusterCredentials(POLARIS_HOST_FOR.get(cloud));
    }

    public static void main(String[] args) throws Exception {
        PolarisClusterCredentials app = new PolarisClusterCredentials(args[0]);
        System.out.println(app.getAccessToken());
    }

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();

    private final String accessToken;

    public PolarisClusterCredentials(String host) {
        accessToken = clientFactory.getPolarisClient(host, properties).getAccessToken();
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String getPolarisId() {
        return AfonProperties.POLARIS_ID.getValueFrom(properties);
    }

    @Override
    public String getPolarisSecret() {
        return AfonProperties.POLARIS_SECRET.getValueFrom(properties);
    }

    @Override
    public String getPolarisHost() {
        return AfonProperties.POLARIS_ENDPOINT.getValueFrom(properties);
    }
}
