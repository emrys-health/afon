package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;

import java.util.Properties;

public class PolarisClusterCredentials implements ClusterCredentials {

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
    private final Properties properties;
    private final String accessToken;

    public PolarisClusterCredentials(AfonCloud cloud) {
        this(cloud, new FilePropertyProvider().getProperties());
    }

    public PolarisClusterCredentials(AfonCloud cloud, Properties properties) {
        this.properties = properties;
        accessToken = clientFactory.getPolarisClient(polarisDomainName(cloud), properties).getAccessToken();
    }

    private String polarisDomainName(AfonCloud cloud) {
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
