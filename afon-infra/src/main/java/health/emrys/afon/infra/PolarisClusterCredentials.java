package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;

import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.cdk.GcpKubernetesPolaris.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;

public class PolarisClusterCredentials implements ClusterCredentials {

    private static final Map<SupportedCloudsEnum, String> POLARIS_HOST_FOR
        = Map.of(AWS, AWS_POLARIS_DOMAIN_NAME,
                 AZURE, AZURE_DOMAIN_NAME,
                 GCP, GCP_DOMAIN_NAME);

    public static ClusterCredentials createClusterCredentials(SupportedCloudsEnum cloud) {
        return new PolarisClusterCredentials(POLARIS_HOST_FOR.get(cloud));
    }


    private final Properties properties;
    private final String accessToken;

    public PolarisClusterCredentials(String host) {
        properties = new AfonPropertiesLoader().getProperties();
        properties.put(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), host);
        accessToken = new AfonPolarisClient(properties, "https").getAccessToken();
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
