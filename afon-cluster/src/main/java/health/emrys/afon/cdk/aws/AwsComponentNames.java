package health.emrys.afon.cdk.aws;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AWS_REGION;
import static health.emrys.afon.config.AfonProperties.CLUSTER_NAME;

public class AwsComponentNames {

    private final String cluster;
    private final String region;

    public AwsComponentNames(Properties properties) {
        cluster = CLUSTER_NAME.getValueFrom(properties);
        region = AWS_REGION.getValueFrom(properties);
    }

    public String getCluster() {
        return cluster;
    }

    public String getRegion() {
        return region;
    }

    public String getClusterRoleName() {
        return "cdktf-eks-cluster-role-" + cluster;
    }

    public String getNodeGroupRoleName() {
        return "cdktf-eks-nodegroup-role-" + cluster;
    }

    public String getVpcId() {
        return "cdktf-eks-vpc-" + cluster;
    }

    public String getSecret() {
        return "cdktf-app-config-secret-" + cluster;
    }

    public String getAccessPolicyName() {
        return "EKS-Secret-Access-Policy-" + cluster;
    }

    public String getLoadBalancerRoleName() {
        return "AmazonEKSLoadBalancerControllerRole" + cluster;
    }
}
