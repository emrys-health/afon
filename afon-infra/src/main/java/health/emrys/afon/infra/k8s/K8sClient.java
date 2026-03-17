package health.emrys.afon.infra.k8s;


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
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.Authentication;
import org.slf4j.Logger;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;


public class K8sClient {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(K8sClient.class);

    public String getIngressExpectingOnlyOneFor(String serviceName) throws ApiException, IncorrectConfigException {
        Optional<List<String>> optionalIngress = getIngressFor(serviceName);
        if (!optionalIngress.isPresent()) {
            throw new IncorrectConfigException(format("No service called '{}'", serviceName));
        }
        List<String> polarisIngress = optionalIngress.get();
        if (polarisIngress.size() != 1) {
            throw new IncorrectConfigException(format("Was expecting 1 service address but got {}", polarisIngress.toString()));
        }
        String polarisHost = polarisIngress.get(0);
        return polarisHost;
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

    public String getServiceExpectingOnlyOneFor(String serviceName) throws ApiException, IncorrectConfigException {
        Optional<List<String>> optionalIngress = getServiceFor(serviceName);
        if (!optionalIngress.isPresent()) {
            throw new IncorrectConfigException(format("No service called '%s'", serviceName));
        }
        List<String> polarisIngress = optionalIngress.get();
        if (polarisIngress.size() != 1) {
            throw new IncorrectConfigException(format("Was expecting 1 service address but got %s", polarisIngress.toString()));
        }
        String polarisHost = polarisIngress.get(0);
        return polarisHost;
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

    protected void registerClient(String endpoint, Authentication authentication, DescribeClusterResponse clusterInfo) {
        ApiClient client = new ClientBuilder()
                .setBasePath(endpoint)
                .setAuthentication(authentication)
                .setVerifyingSsl(true)
                .setCertificateAuthority(Base64.getDecoder().decode(clusterInfo.cluster().certificateAuthority().data()))
                .build();
        Configuration.setDefaultApiClient(client);
    }

    protected void smokeTest() {
        try {
            for (V1Pod pod : listPods()) {
                System.out.printf("Pod: %s, IP: %s%n",
                                  pod.getMetadata().getName(),
                                  pod.getStatus().getPodIP());
            }
            for (V1Service service : listServices()) {
                System.out.println(service.getMetadata().getName());
                List<String> externalIPs = service.getSpec().getExternalIPs();
                for (String externalIP : externalIPs) {
                    System.out.println(externalIP);
                }
            }
        } catch (ApiException e) {
            logger.error("could not talk to cluster ", e);
        }
    }
}
