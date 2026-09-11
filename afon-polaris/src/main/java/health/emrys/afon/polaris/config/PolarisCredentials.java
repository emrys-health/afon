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
        this.properties.setValue(POLARIS_ENDPOINT, new PolarisEndpoint(cloud, properties).getEndpoint());
        this.accessToken = clientFactory.createClient(this.properties).getAccessToken();
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
