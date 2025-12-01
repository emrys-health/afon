package health.emrys.afon.infra.k8s;

import health.emrys.afon.infra.PolarisCredentialGrabber;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.infra.Defaults.POLARIS_POD_NAME;
import static health.emrys.afon.infra.Defaults.POLARIS_SERVICE_NAME;
import static java.lang.String.format;

public class ClusterState {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterState.class);

    private final String           accessToken;
    private final GenericK8sClient k8sClient;
    private final String           polarisHost;

    public ClusterState() throws IOException, ApiException, IncorrectConfigException {
        k8sClient = new GenericK8sClient();
        V1Pod polarisPod = k8sClient.getPodExpectingOnlyOneStartingWith(POLARIS_POD_NAME);
        accessToken = new PolarisCredentialGrabber().getCredentials(k8sClient.getLogsOf(polarisPod.getMetadata().getName()));
        polarisHost = k8sClient.getIngressExpectingOnlyOneFor(POLARIS_SERVICE_NAME);
        logger.info("Polaris is available to the outside world at: {}", polarisHost);
    }


    public String getCredentials() {
        return accessToken;
    }

    public String getPolarisEndpoint() {
        return format("%s:8181", polarisHost);
    }

    public String getPolarisHost() {
        return polarisHost;
    }
}
