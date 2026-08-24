package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;

import static health.emrys.afon.config.AfonProperty.*;

public class PolarisClusterCredentials implements ClusterCredentials {

    private final PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
    private final AfonPropertyProvider properties;
    private final String accessToken;

    public PolarisClusterCredentials(AfonCloud cloud, AfonPropertyProvider properties) {
        this.properties = properties;
        this.properties.setValue(POLARIS_ENDPOINT, polarisDomainName(cloud));
        this.accessToken = clientFactory.createClient(this.properties).getAccessToken();
    }

    private String polarisDomainName(AfonCloud cloud) {
        switch ( cloud ) {
            case AWS:
                return properties.getValue(AWS_POLARIS_DOMAIN_NAME);
            case AZURE:
                return properties.getValue(AZURE_DOMAIN_NAME);
            case GCP:
                return properties.getValue(GCP_DOMAIN_NAME);
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
        return properties.getValue(POLARIS_ID);
    }

    @Override
    public String getPolarisSecret() {
        return properties.getValue(POLARIS_SECRET);
    }

    @Override
    public String getPolarisHost() {
        return properties.getValue(POLARIS_ENDPOINT);
    }

}
