package health.emrys.afon.cluster;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSSessionCredentialsProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.EKSAuthentication;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;

import java.util.Base64;

import static health.emrys.afon.config.AfonProperty.*;

public class EksClusterClient extends ClusterClient {

    public EksClusterClient(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    protected ApiClient buildClient(AfonPropertyProvider properties) {
        Region region = Region.of(properties.getValue(AWS_REGION));
        AWSCredentials awsCredentials = new BasicAWSCredentials(
                properties.getValue(AWS_ACCESS_KEY_ID),
                properties.getValue(AWS_SECRET_ACCESS_KEY));

        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        DescribeClusterResponse clusterInfo;
        try (EksClient eksClient = EksClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()) {
            clusterInfo = eksClient.describeCluster(
                    DescribeClusterRequest.builder().name(getClusterName()).build());
        }

        Authentication authentication = new EKSAuthentication(new STSSessionCredentialsProvider(awsCredentials),
                region.toString(), getClusterName());
        String endpoint = clusterInfo.cluster().endpoint();
        return new ClientBuilder()
                .setBasePath(endpoint)
                .setAuthentication(authentication)
                .setVerifyingSsl(true)
                .setCertificateAuthority(Base64.getDecoder().decode(clusterInfo.cluster().certificateAuthority().data()))
                .build();
    }


}
