package health.emrys.afon.infra.k8s;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSSessionCredentialsProvider;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest;
import io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedServiceRequest;
import io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.EKSAuthentication;
import org.slf4j.Logger;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static java.lang.String.format;

public class EksInfoClient {

    static final  Logger logger = org.slf4j.LoggerFactory.getLogger(EksInfoClient.class);

    public static void main(String[] args) {
        EksInfoClient app = new EksInfoClient(CLUSTER_NAME, new AfonPropertiesLoader().getProperties());
        try {
            for (V1Pod pod : app.listPods()) {
                System.out.printf("Pod: %s, IP: %s%n",
                                  pod.getMetadata().getName(),
                                  pod.getStatus().getPodIP());
            }
            for (V1Service service : app.listServices()) {
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

    public EksInfoClient(String clusterName, Properties properties) {
        Region region = Region.of(AWS_REGION.getValueFrom(properties));
        // Step 1: Use AWS SDK to get cluster endpoint + CA
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        EksClient eksClient = EksClient.builder()
                                       .region(region)
                                       .credentialsProvider(credentialsProvider)
                                       .build();

        DescribeClusterResponse clusterInfo = eksClient.describeCluster(
                DescribeClusterRequest.builder().name(clusterName).build());

        AWSCredentials awsCredentials = new BasicAWSCredentials(
                AWS_ACCESS_KEY_ID.getValueFrom(properties),
                AWS_SECRET_ACCESS_KEY.getValueFrom(properties));
        var authentication = new EKSAuthentication(new STSSessionCredentialsProvider(awsCredentials),
                                                   region.toString(),
                                                   clusterName);

        ApiClient client = new ClientBuilder()
                .setBasePath(clusterInfo.cluster().endpoint())
                .setAuthentication(authentication)
                .setVerifyingSsl(true)
                .setCertificateAuthority(Base64.getDecoder().decode(clusterInfo.cluster().certificateAuthority().data()))
                .build();
        Configuration.setDefaultApiClient(client);
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
        String polarisHost = polarisIngress.get(0);
        return polarisHost;
    }

    public Optional<List<String>> getIngressFor(String serviceName) throws ApiException {
        for (V1Service service : listServices()) {
            if (service.getMetadata().getName().equals(serviceName)) {
                List<V1LoadBalancerIngress> ingress = service.getStatus().getLoadBalancer().getIngress();
                var serviceNames = ingress.stream().map(x -> x.getHostname()).collect(Collectors.toList());
                return Optional.of(serviceNames);
            }
        }
        return Optional.empty();
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
}

