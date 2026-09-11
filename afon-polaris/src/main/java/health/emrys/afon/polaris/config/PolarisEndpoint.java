package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.*;

public class PolarisEndpoint {

    private final AfonCloud cloud;
    private final String endpoint;

    public PolarisEndpoint(AfonCloud cloud, AfonPropertyProvider properties) {
        this.cloud = cloud;
        this.endpoint = polarisDomainName(cloud, properties);
    }

    private String polarisDomainName(AfonCloud cloud, AfonPropertyProvider properties) {
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

    public AfonCloud getCloud() {
        return cloud;
    }

    public String getEndpoint() {
        return endpoint;
    }

}
