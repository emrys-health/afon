package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;

import java.util.Properties;

public class PolarisClusterCredentials implements ClusterCredentials {

    public static ClusterCredentials createClusterCredentials(AfonCloud cloud) {
        return new PolarisClusterCredentials(polarisDomainName(cloud));
    }

    private static String polarisDomainName(AfonCloud cloud) {
        Properties properties = new FilePropertyProvider().getProperties();
        switch ( cloud ) {
            case AWS:
                return AfonProperties.AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties);
            case AZURE:
                return AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(properties);
            case GCP:
                return AfonProperties.GCP_DOMAIN_NAME.getValueFrom(properties);
            default:
                throw new RuntimeException("Unsupported cloud "+cloud);
        }

    }

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
    private final Properties properties;
    private final String accessToken;

    public PolarisClusterCredentials(String domain) {
        properties = new FilePropertyProvider().getProperties();
        accessToken = clientFactory.getPolarisClient(domain, properties).getAccessToken();
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
