package health.emrys.afon.k8s;

import health.emrys.afon.infra.PolarisCredentialGrabber;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.infra.Defaults.POLARIS_POD_NAME;
import static health.emrys.afon.infra.Defaults.POLARIS_INGRESS_NAME;

public class ClusterState implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterState.class);
    public static final String ACCESS_TOKEN_SYS_PROP = "ACCESS_TOKEN";

    private final String           accessToken;
    private final GenericK8sClient k8sClient;
    private final String           polarisHost;

    public ClusterState() throws IOException, ApiException, IncorrectConfigException {
        k8sClient = new GenericK8sClient();
        V1Pod  polarisPod = k8sClient.getPodExpectingOnlyOneStartingWith(POLARIS_POD_NAME);
        String podName    = polarisPod.getMetadata().getName();
        logger.info("Pod Name: {}", podName);
        String tokenFromSysProp = System.getProperty(ACCESS_TOKEN_SYS_PROP);
        if (tokenFromSysProp == null) {
            logger.info("Access Token not set as the system property " + ACCESS_TOKEN_SYS_PROP);
            accessToken = new PolarisCredentialGrabber().getCredentials(k8sClient.getLogsOf(podName));
        } else {
            accessToken = tokenFromSysProp;
        }
        polarisHost = k8sClient.getIngressExpectingOnlyOneFor(POLARIS_INGRESS_NAME);
        logger.info("Polaris is available to the outside world at: {}", polarisHost);
    }


    public String getCredentials() {
        return accessToken;
    }

    public String getPolarisHost() {
        return polarisHost;
    }

    @Override
    public void close() throws Exception {
        k8sClient.close();
    }
}
