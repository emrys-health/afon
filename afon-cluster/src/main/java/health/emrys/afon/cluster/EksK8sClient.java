package health.emrys.afon.cluster;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSSessionCredentialsProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.EKSAuthentication;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;

import static health.emrys.afon.config.AfonProperty.*;

public class EksK8sClient extends K8sClient {

    public EksK8sClient(AfonPropertyProvider properties){
        super(properties);
        Region region = Region.of(properties.getValue(AWS_REGION));
        AWSCredentials awsCredentials = new BasicAWSCredentials(
                properties.getValue(AWS_ACCESS_KEY_ID),
                properties.getValue(AWS_SECRET_ACCESS_KEY));
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


}

