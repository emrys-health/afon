package health.emrys.afon.cluster;


import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest;
import io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedServiceRequest;
import io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api.APIlistIngressForAllNamespacesRequest;
import io.kubernetes.client.openapi.models.*;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static java.lang.String.format;


public abstract class ClusterClient implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterClient.class);

    private final String clusterName;
    private final ApiClient apiClient;

    public ClusterClient(AfonPropertyProvider properties) {
        this.clusterName = properties.getValue(CLUSTER_NAME);
        this.apiClient = buildClient(properties);
        Configuration.setDefaultApiClient(apiClient);
    }

    protected abstract ApiClient buildClient(AfonPropertyProvider properties);

    public String getClusterName() {
        return clusterName;
    }

    public String getIngressExpectingOnlyOneFor(String serviceName) throws ApiException, IncorrectConfigException {
        Optional<List<String>> optionalIngress = getIngressFor(serviceName);
        if (!optionalIngress.isPresent()) {
            throw new IncorrectConfigException(format("No service called '{}'", serviceName));
        }
        List<String> polarisIngress = optionalIngress.get();
        if (polarisIngress.size() != 1) {
            throw new IncorrectConfigException(format("Was expecting 1 service address but got {}", polarisIngress.toString()));
        }
        return polarisIngress.get(0);
    }

    public Optional<List<String>> getIngressFor(String serviceName) throws ApiException {
        for (V1Ingress service : listIngress()) {
            if (service.getMetadata().getName().equals(serviceName)) {
                List<V1IngressRule> ingress      = service.getSpec().getRules();
                var                                serviceNames = ingress.stream().map(x -> x.getHost()).collect(Collectors.toList());
                return Optional.of(serviceNames);
            }
        }
        return Optional.empty();
    }

    public String getUniqueService(String serviceName) throws ApiException, IncorrectConfigException {
        logger.info(format("Querying cluster %s for service name %s", clusterName, serviceName));

        Optional<List<String>> optionalIngress = getServiceFor(serviceName);
        if (!optionalIngress.isPresent()) {
            throw new IncorrectConfigException(format("No service called '%s'", serviceName));
        }
        List<String> polarisIngress = optionalIngress.get();
        if (polarisIngress.size() != 1) {
            throw new IncorrectConfigException(format("Was expecting 1 service address but got %s", polarisIngress));
        }
        return polarisIngress.get(0);
    }

    public Optional<List<String>> getServiceFor(String serviceName) throws ApiException {
        for (V1Service service : listServices()) {
            logger.info("Service: {}", service.getMetadata().getName());
            if (service.getMetadata().getName().equals(serviceName)) {
                List<V1LoadBalancerIngress> ingress = service.getStatus().getLoadBalancer().getIngress();
                var serviceNames = ingress.stream().map(x -> getEndpoint(x)).collect(Collectors.toList());
                return Optional.of(serviceNames);
            }
        }
        return Optional.empty();
    }

    private String getEndpoint(V1LoadBalancerIngress x) {
        if (x.getHostname() == null || x.getHostname().isEmpty()) {
            return x.getIp();
        }
        return x.getHostname();
    }

    public List<V1Ingress> listIngress() throws ApiException {
        NetworkingV1Api api     = new NetworkingV1Api();
        APIlistIngressForAllNamespacesRequest ingress = api.listIngressForAllNamespaces();
        return ingress.execute().getItems();
    }

    public List<V1Service> listServices() throws ApiException {
        CoreV1Api                       api     = new CoreV1Api();
        APIlistNamespacedServiceRequest request = api.listNamespacedService("default");
        return request.execute().getItems();
    }

    public List<V1Pod> listPods() throws ApiException {
        CoreV1Api                   api     = new CoreV1Api();
        APIlistNamespacedPodRequest request = api.listNamespacedPod("default");
        List<V1Pod>                 pods    = request.execute().getItems();
        return pods;
    }

    public V1Pod getPodExpectingOnlyOneStartingWith(String podName) throws ApiException {
        return listPods().stream().filter(x -> x.getMetadata().getName().startsWith(podName)).findFirst().orElseThrow();
    }

    public String getLogsOf(String pod) throws ApiException {
        CoreV1Api api = new CoreV1Api();
        APIreadNamespacedPodLogRequest request = api.readNamespacedPodLog(pod, "default");
        return request.execute().toString();
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing Kubernetes client");
        if (apiClient != null && apiClient.getHttpClient() != null) {
            OkHttpClient httpClient = apiClient.getHttpClient();
            if (httpClient.cache() != null) {
                logger.info("Closing cache");
                httpClient.cache().close();
            }
            if (httpClient.connectionPool() != null) {
                logger.info("Closing connection pool");
                httpClient.connectionPool().evictAll();
            }
            Dispatcher dispatcher = httpClient.dispatcher();
            if (dispatcher != null && dispatcher.executorService() != null) {
                logger.info("Closing executor service");
                dispatcher.executorService().shutdown();
            }
        }
        Configuration.setDefaultApiClient(null);
    }

}
