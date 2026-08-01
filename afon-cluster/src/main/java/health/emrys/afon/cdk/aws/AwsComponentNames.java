package health.emrys.afon.cdk.aws;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.CLUSTER_NAME;

public class AwsComponentNames {

    private final String prefix;

    public AwsComponentNames(Properties properties) {
        prefix = CLUSTER_NAME.getValueFrom(properties);
    }

    public String getClusterRoleName() {
        return "cdktf-eks-cluster-role-" + prefix;
    }

    public String getNodeGroupRoleName() {
        return "cdktf-eks-nodegroup-role-" + prefix;
    }

    public String getVpcId() {
        return "cdktf-eks-vpc-" + prefix;
    }

    public String getSecret() {
        return "cdktf-app-config-secret-" + prefix;
    }

    public String getAccessPolicyName() {
        return "EKS-Secret-Access-Policy-" + prefix;
    }

    public String getLoadBalancerRoleName() {
        return "AmazonEKSLoadBalancerControllerRole" + prefix;
    }
}
