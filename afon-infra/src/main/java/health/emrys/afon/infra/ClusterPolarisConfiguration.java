package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.EksInfoClient;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.infra.Defaults.*;
import static java.lang.String.format;

public class ClusterPolarisConfiguration {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    public static void main(String[] args) {

        Properties properties  = new AfonPropertiesLoader().getProperties();
        var        app         = new ClusterPolarisConfiguration(CLUSTER_NAME, properties);
        try {
            app.initializePolaris(POLARIS_SERVICE_NAME, POLARIS_POD_NAME);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final String     clusterName;
    private final Properties properties;

    public ClusterPolarisConfiguration(String clusterName, Properties properties) {
        this.clusterName = clusterName;
        this.properties  = properties;
    }

    public void initializePolaris(String polarisServiceName,
                                  String polarisPodName) throws ApiException, IncorrectConfigException, IOException {
        logger.info("We assume that an EKS cluster called {} is running...", clusterName);
        var    eksClient    = new EksInfoClient(clusterName, properties);
        var    tokenGrabber = new PolarisCredentialGrabber();
        String accessToken  = tokenGrabber.getCredentials(eksClient.getLogsOf(polarisPodName));
        addPolarisEndpointToProperties(polarisServiceName, eksClient);
        PolarisConfigureMain polarisClient = new PolarisConfigureMain();
        polarisClient.createAllCatalogs(properties, accessToken);
    }

    public void addPolarisEndpointToProperties(String polarisServiceName, EksInfoClient eksClient) throws ApiException, IncorrectConfigException {
        String polarisHost = eksClient.getIngressExpectingOnlyOneFor(polarisServiceName);
        logger.info("Polaris is available to the outisde world at: {}", polarisHost);
        properties.setProperty(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), format("%s:8181", polarisHost));
    }

}
