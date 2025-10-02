package health.emrys.afon.k8s;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSSessionCredentialsProvider;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.EKSAuthentication;
import okhttp3.OkHttpClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;

import java.util.Base64;

public class EksPodLister {
    public static void main(String[] args) throws Exception {
        String clusterName = "spark-cluster";
        Region region = Region.EU_WEST_2;

        // Step 1: Use AWS SDK to get cluster endpoint + CA
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        EksClient eksClient = EksClient.builder()
                                       .region(region)
                                       .credentialsProvider(credentialsProvider)
                                       .build();

        DescribeClusterResponse clusterInfo = eksClient.describeCluster(
                DescribeClusterRequest.builder().name(clusterName).build());

        String endpoint = clusterInfo.cluster().endpoint();
        String certAuthData = clusterInfo.cluster().certificateAuthority().data();

        // Step 2: Generate authentication token (same as `aws eks get-token`)
        StsClient stsClient = StsClient.builder()
                                       .region(region)
                                       .credentialsProvider(credentialsProvider)
                                       .build();

        System.out.println(clusterInfo.cluster().arn());

//        ApiClient client = new ClientBuilder().setBasePath(endpoint).build();

        // EKS token = base64url-encoded presigned URL
//        String token = getEksToken(stsClient, clusterName);


        var properties = new AfonPropertiesLoader().getProperties();
        AWSCredentials awsCredentials = new BasicAWSCredentials(
                AfonProperties.AWS_ACCESS_KEY_ID.getValueFrom(properties),
                AfonProperties.AWS_SECRET_ACCESS_KEY.getValueFrom(properties));
        var authentication = new EKSAuthentication(new STSSessionCredentialsProvider(awsCredentials), region.toString(), clusterName);

        // Step 3: Configure Kubernetes ApiClient
        ApiClient client = new ClientBuilder()
                .setBasePath(endpoint)
                .setAuthentication(authentication)
                .setVerifyingSsl(true)
                .setCertificateAuthority(Base64.getDecoder().decode(certAuthData))
                .build();
//
        Configuration.setDefaultApiClient(client);

        // Step 4: List pods
        CoreV1Api api = new CoreV1Api();
        var pods = api.listNamespacedPod("default");

        for (V1Pod pod : pods.execute().getItems()) {
            System.out.printf("Pod: %s, IP: %s%n",
                              pod.getMetadata().getName(),
                              pod.getStatus().getPodIP());
        }
    }

//    public static String getEksToken(StsClient stsClient, String clusterName) {
//        Aws4Signer signer = Aws4Signer.create();
//
//        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
//                                                       .method(SdkHttpMethod.GET)
//                                                       .uri(URI.create("https://sts.amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15"))
//                                                       .putHeader("x-k8s-aws-id", clusterName)
//                                                       .build();
//
//        SdkHttpFullRequest signed = signer.sign(request, stsClient.serviceClientConfiguration().credentialsProvider().);
//
//        // Encode the presigned URL as base64url without padding
//        String presignedUrl = signed.getUri().toString();
//        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(presignedUrl.getBytes());
//
//        return "k8s-aws-v1." + encoded;
//    }
}

