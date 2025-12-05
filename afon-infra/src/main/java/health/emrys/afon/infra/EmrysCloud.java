package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import io.kubernetes.client.openapi.ApiException;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonPropertiesLoader.add;

public class EmrysCloud {

    private final String            accessToken;
    private final AfonPolarisClient client;
    private final String            polarisId;
    private final String            polarisSecret;
    private final ClusterState      clusterState;

    public EmrysCloud() throws IncorrectConfigException, IOException, ApiException {
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        clusterState = new ClusterState();
        Properties props = add(clusterState.getCredentials(), properties);
        props.setProperty(POLARIS_ENDPOINT.getPropertyName(), clusterState.getPolarisHost());
        polarisId     = props.getProperty(AfonProperties.POLARIS_ID.getPropertyName());
        polarisSecret = props.getProperty(AfonProperties.POLARIS_SECRET.getPropertyName());
        client        = new AfonPolarisClient(props, "https");
        accessToken   = client.getAccessToken();
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getPolarisId() {
        return polarisId;
    }

    public String getPolarisSecret() {
        return polarisSecret;
    }

    public String getPolarisHost() {
        return clusterState.getPolarisHost();
    }
}
