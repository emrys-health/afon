package health.emrys.afon.infra;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.EksInfoClient;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.util.Properties;

import static health.emrys.afon.infra.Defaults.*;

public class AwsInfraExplorer {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsInfraExplorer.class);

    public static final void main(String[] args) {
        String clusterName = CLUSTER_NAME;
        logger.info("Exploring infra. I'm assuming that cluster {} is up and running...", clusterName);
        Properties       properties = new AfonPropertiesLoader().getProperties();
        AwsInfraExplorer app        = new AwsInfraExplorer();
        try {
            app.printJupyterDetails(clusterName, properties, JUPYTER_SERVICE_NAME);
            app.printSparkDetails(clusterName, properties, SPARK_SERVICE_NAME);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final LogParser logParser;

    public AwsInfraExplorer() {
        logParser = new LogParser();
    }

    private void printJupyterDetails(String clusterName,
                                     Properties properties,
                                     String jupyterServiceName) throws ApiException, IncorrectConfigException {
        var eksClient = new EksInfoClient(clusterName, properties);
        String jupyterPodName = eksClient.getPodExpectingOnlyOneStartingWith("jupyter").getMetadata().getName();
        logger.info("Cluster querying: {} for service name {}", clusterName, jupyterServiceName);
        String jupyterHost = eksClient.getServiceExpectingOnlyOneFor(jupyterServiceName);
        logger.info("Cluster querying: {} for the logs of pod {}", clusterName, jupyterPodName);
        String logs = eksClient.getLogsOf(jupyterPodName);
        String uri  = logParser.searchForTextAfter("http://127.0.0.1", logs);
        logger.info("Jupyter can be found at:\nhttp://{}{}", jupyterHost, uri);
    }

    private void printSparkDetails(String clusterName,
                                   Properties properties,
                                   String sparkServiceName) throws IncorrectConfigException, ApiException {
        var eksClient = new EksInfoClient(clusterName, properties);
        logger.info("Cluster querying: {} for service name {}", clusterName, sparkServiceName);
        String sparkHost = eksClient.getServiceExpectingOnlyOneFor(sparkServiceName);
        logger.info("Spark can be found at:\nhttp://{}:8080/", sparkHost);
    }

}
