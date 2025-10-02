package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.EksInfoClient;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static java.lang.String.format;

public class ClusterPolarisConfiguration {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    public static void main(String[] args) {
        if (args.length != 1) {
            logger.error("""
Need Polaris's acess token as an argument. Find it by running:
kubectl logs polaris 2>&1  | tr '\\r\\n' '\\275\\276' | tr -d "[:cntrl:]" | tr "\\275\\276" "\\r\\n" | grep "root principal" | perl -pe s/.*root\\ principal\\ credentials:\\ //g
                                 """);
        } else {
            String     clusterName = "spark-cluster";
            Properties properties  = new AfonPropertiesLoader().getProperties();
            var        app         = new ClusterPolarisConfiguration(clusterName, properties);
            try {
                app.initializePolaris("polaris-lb", args[0]);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private final String     clusterName;
    private final Properties properties;

    public ClusterPolarisConfiguration(String clusterName, Properties properties) {
        this.clusterName = clusterName;
        this.properties  = properties;
    }

    public void initializePolaris(String polarisServiceName, String accessToken) throws ApiException, IncorrectConfigException, IOException {
        logger.info("We assume that an EKS cluster called {} is running...", clusterName);
        var eksClient = new EksInfoClient(clusterName, properties);
        addPolarisEndpointToProperties(polarisServiceName, eksClient);
        PolarisConfigureMain polarisClient = new PolarisConfigureMain();
        polarisClient.createAllCatalogs(properties, accessToken);
    }

    private void addPolarisEndpointToProperties(String polarisServiceName, EksInfoClient eksClient) throws ApiException, IncorrectConfigException {
        Optional<List<String>> optionalIngress = eksClient.getIngressFor(polarisServiceName);
        if (!optionalIngress.isPresent()) {
            throw new IncorrectConfigException(format("No service called '{}' in cluster'{}", polarisServiceName, clusterName));
        }
        List<String> polarisIngress = optionalIngress.get();
        if (polarisIngress.size() != 1) {
            throw new IncorrectConfigException(format("Was expecting 1 service adderss but got {}", polarisIngress.toString()));
        }
        String polarisHost = polarisIngress.get(0);
        logger.info("Polaris is available to the outisde world at: {}", polarisHost);
        properties.setProperty(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), format("%s:8181", polarisHost));
    }

}
