package health.emrys.afon.cdk.aws;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;

public class AwsComponentNames {

    private final String cluster;
    private final String region;

    public AwsComponentNames(AfonPropertyProvider properties) {
        cluster = properties.getValue(CLUSTER_NAME);
        region = properties.getValue(AWS_REGION);
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

    public String getEgressIpName() {
        return "cdktf-eks-egress-ip-" + cluster;
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
