package health.emrys.afon.infra.k8s;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSSessionCredentialsProvider;
import health.emrys.afon.config.AfonPropertiesLoader;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.EKSAuthentication;
import org.slf4j.Logger;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class EksInfoClient extends K8sClient {

    static final  Logger logger = org.slf4j.LoggerFactory.getLogger(EksInfoClient.class);

    public static void main(String[] args) {
        Properties    properties = new AfonPropertiesLoader().getProperties();
        EksInfoClient app        = new EksInfoClient(CLUSTER_NAME.getValueFrom(properties), properties);
        app.smokeTest();
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
        Authentication authentication = new EKSAuthentication(new STSSessionCredentialsProvider(awsCredentials),
                                                              region.toString(),
                                                              clusterName);
        String endpoint = clusterInfo.cluster().endpoint();
        registerClient(endpoint, authentication, clusterInfo);
    }
}

