package health.emrys.afon.cluster;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSSessionCredentialsProvider;
import health.emrys.afon.config.AfonPropertiesLoader;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.EKSAuthentication;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class EksK8sClient extends K8sClient {

    public EksK8sClient() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public EksK8sClient(Properties properties){
        super(properties);
        Region region = Region.of(AWS_REGION.getValueFrom(properties));
        AWSCredentials awsCredentials = new BasicAWSCredentials(
                AWS_ACCESS_KEY_ID.getValueFrom(properties),
                AWS_SECRET_ACCESS_KEY.getValueFrom(properties));
        registerClient(region, awsCredentials);
    }

    private void registerClient(Region region, AWSCredentials credentials) {
        // Step 1: Use AWS SDK to get cluster endpoint + CA
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        EksClient eksClient = EksClient.builder()
                                       .region(region)
                                       .credentialsProvider(credentialsProvider)
                                       .build();

        DescribeClusterResponse clusterInfo = eksClient.describeCluster(
                DescribeClusterRequest.builder().name(getClusterName()).build());

        Authentication authentication = new EKSAuthentication(new STSSessionCredentialsProvider(credentials),
                                                              region.toString(), getClusterName());
        String endpoint = clusterInfo.cluster().endpoint();
        registerClient(endpoint, authentication, clusterInfo);
    }

    public static void main(String[] args) {
        EksK8sClient app = new EksK8sClient();
        app.smokeTest();
    }

}

