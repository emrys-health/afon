package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import io.kubernetes.client.openapi.ApiException;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.config.PolarisConfigClient.addPolarisCredentialsTo;

/**
 * This class gets the credenital from the logs.
 * Now we're using Postgres, the credential is stored there not
 * generated every time Polaris starts.
 */
@Deprecated
public class EmrysCloud implements ClusterCredentials {

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();

    private final String            accessToken;
    private final AfonPolarisClient client;
    private final String            polarisId;
    private final String            polarisSecret;
    private final ClusterState      clusterState;

    @Deprecated
    public EmrysCloud() throws IncorrectConfigException, IOException, ApiException {
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        clusterState = new ClusterState();
        Properties props = addPolarisCredentialsTo(clusterState.getCredentials(), properties);
        props.setProperty(POLARIS_ENDPOINT.getName(), clusterState.getPolarisHost());
        polarisId     = props.getProperty(AfonProperties.POLARIS_ID.getName());
        polarisSecret = props.getProperty(AfonProperties.POLARIS_SECRET.getName());
        client        = clientFactory.getPolarisClient(props, "https");
        accessToken   = client.getAccessToken();
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String getPolarisId() {
        return polarisId;
    }

    @Override
    public String getPolarisSecret() {
        return polarisSecret;
    }

    @Override
    public String getPolarisHost() {
        return clusterState.getPolarisHost();
    }
}
