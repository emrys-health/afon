package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.*;

public class PolarisCredentials {

    private final PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
    private final AfonCloud cloud;
    private final AfonPropertyProvider properties;
    private final String accessToken;

    public PolarisCredentials(AfonCloud cloud, AfonPropertyProvider properties) {
        this.cloud = cloud;
        this.properties = properties;
        this.properties.setValue(POLARIS_ENDPOINT, polarisDomainName(cloud));
        this.accessToken = clientFactory.createClient(this.properties).getAccessToken();
    }

    private String polarisDomainName(AfonCloud cloud) {
        switch ( cloud ) {
            case AWS:
                return properties.getValue(AWS_POLARIS_DOMAIN_NAME);
            case AZURE:
                return properties.getValue(AZURE_POLARIS_DOMAIN_NAME);
            case GCP:
                return properties.getValue(GCP_POLARIS_DOMAIN_NAME);
            default:
                throw new RuntimeException("Unsupported cloud "+cloud);
        }
    }

    public String getAccessToken() {
        return accessToken;
    }

    public AfonCloud getCloud() {
        return cloud;
    }

    public String getPolarisId() {
        return properties.getValue(POLARIS_ID);
    }

    public String getPolarisSecret() {
        return properties.getValue(POLARIS_SECRET);
    }

    public String getPolarisHost() {
        return properties.getValue(POLARIS_ENDPOINT);
    }

}
