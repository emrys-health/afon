package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;

import java.util.Properties;

public class PolarisClusterCredentials implements ClusterCredentials {

    private final PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
    private final Properties properties;
    private final String accessToken;

    public PolarisClusterCredentials(AfonCloud cloud) {
        this(cloud, new FilePropertyProvider().getProperties());
    }

    public PolarisClusterCredentials(AfonCloud cloud, Properties properties) {
        this.properties = properties;
        accessToken = clientFactory.createClient(polarisDomainName(cloud), properties).getAccessToken();
    }

    private String polarisDomainName(AfonCloud cloud) {
        switch ( cloud ) {
            case AWS:
                return AfonProperty.AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties);
            case AZURE:
                return AfonProperty.AZURE_DOMAIN_NAME.getValueFrom(properties);
            case GCP:
                return AfonProperty.GCP_DOMAIN_NAME.getValueFrom(properties);
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
        return AfonProperty.POLARIS_ID.getValueFrom(properties);
    }

    @Override
    public String getPolarisSecret() {
        return AfonProperty.POLARIS_SECRET.getValueFrom(properties);
    }

    @Override
    public String getPolarisHost() {
        return AfonProperty.POLARIS_ENDPOINT.getValueFrom(properties);
    }

}
