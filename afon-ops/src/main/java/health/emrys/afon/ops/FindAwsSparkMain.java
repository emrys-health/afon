package health.emrys.afon.ops;

import health.emrys.afon.cluster.EksK8sClient;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;

import static health.emrys.afon.cluster.Defaults.SPARK_SERVICE_NAME;
import static java.lang.String.format;

public class FindAwsSparkMain {

    private void printSparkDetails(String sparkServiceName) throws IncorrectConfigException, ApiException {
        var properties = new FilePropertyProvider();
        var eksClient = new EksK8sClient(properties);
        String sparkHost = eksClient.getUniqueService(sparkServiceName);
        System.out.println(format("Spark can be found at:\nhttp://%s:8080/", sparkHost));
    }

    public static final void main(String[] args) {
        FindAwsSparkMain app = new FindAwsSparkMain();
        try {
            app.printSparkDetails(SPARK_SERVICE_NAME);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
