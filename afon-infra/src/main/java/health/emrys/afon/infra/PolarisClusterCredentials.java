package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;

import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.GcpKubernetesPolaris.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;

public class PolarisClusterCredentials implements ClusterCredentials {

    private static final Properties properties = new AfonPropertiesLoader().getProperties();
    private static final Map<SupportedCloudsEnum, String> POLARIS_HOST_FOR
        = Map.of(AWS, AfonProperties.AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties),
                 AZURE, AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(properties),
                 GCP, GCP_DOMAIN_NAME);


    public static ClusterCredentials createClusterCredentials(SupportedCloudsEnum cloud) {
        return new PolarisClusterCredentials(POLARIS_HOST_FOR.get(cloud));
    }
    private final String accessToken;

    public PolarisClusterCredentials(String host) {
        accessToken = new AfonPolarisClient(host, properties).getAccessToken();
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
