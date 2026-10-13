package health.emrys.afon.cdk.aws;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static java.lang.String.format;

/**
 * The names of the AWS components of a stack, all built from the cluster name {@code <stack-type>-<CLUSTER_NAME>},
 * eg {@code keycloak-emrys-cluster}, so that the components of each stack type are named apart.
 * The AWS code must name the components only via this class, never from {@code CLUSTER_NAME} directly.
 */
public class AwsComponentNames {

    /**
     * The max length of an IAM role name.
     */
    static final int MAX_ROLE_NAME_LENGTH = 64;

    private final String cluster;
    private final String region;

    public AwsComponentNames(AfonPropertyProvider properties, StackId stackId) {
        if (stackId.cloud() != AWS) {
            throw new IllegalArgumentException(format("Stack %s is not an AWS stack", stackId));
        }
        cluster = clusterName(properties.getValue(CLUSTER_NAME), stackId);
        region = properties.getValue(AWS_REGION);
        checkRoleNameLength(getClusterRoleName(), getNodeGroupRoleName(), getLoadBalancerRoleName(),
                            getPolarisLogShipperRoleName(), getEbsCsiDriverRoleName());
    }

    /**
     * @return the cluster name of the stack, ie {@code <stack-type>-<clusterName>}
     */
    static String clusterName(String clusterName, StackId stackId) {
        return stackId.type() + "-" + clusterName;
    }

    /**
     * Fails at synth time, rather than at apply time, if a role name is too long for IAM.
     */
    private void checkRoleNameLength(String... roleNames) {
        for (String roleName : roleNames) {
            if (roleName.length() > MAX_ROLE_NAME_LENGTH) {
                throw new IllegalArgumentException(format(
                    "IAM role name '%s' is %d chars, more than the max %d: shorten %s, as the cluster name '%s' is <stack-type>-%s",
                    roleName, roleName.length(), MAX_ROLE_NAME_LENGTH, CLUSTER_NAME, cluster, CLUSTER_NAME));
            }
        }
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

    public String getInternetGatewayName() {
        return "cdktf-eks-igw-" + cluster;
    }

    /**
     * @param index the index of the subnet, from 1
     */
    public String getPublicSubnetName(int index) {
        return format("cdktf-eks-public-%d-%s", index, cluster);
    }

    public String getPublicRouteTableName() {
        return "cdktf-eks-public-rt-" + cluster;
    }

    /**
     * @param index the index of the subnet, from 1
     */
    public String getPrivateSubnetName(int index) {
        return format("cdktf-eks-private-%d-%s", index, cluster);
    }

    public String getPrivateRouteTableName() {
        return "cdktf-eks-private-rt-" + cluster;
    }

    public String getNatGatewayName() {
        return "cdktf-eks-nat-" + cluster;
    }

    public String getEgressIpName() {
        return "cdktf-eks-egress-ip-" + cluster;
    }

    public String getOidcProviderName() {
        return cluster + "-oidc-provider";
    }

    /**
     * The Secrets Manager secret that stacks deployed before it was removed created and nothing read, kept to be cleaned up.
     */
    public String getSecret() {
        return "cdktf-app-config-secret-" + cluster;
    }

    /**
     * The IAM policy reading {@link #getSecret()}, kept to be cleaned up like it.
     */
    public String getAccessPolicyName() {
        return "EKS-Secret-Access-Policy-" + cluster;
    }

    public String getLoadBalancerRoleName() {
        return "AmazonEKSLoadBalancerControllerRole" + cluster;
    }

    public String getPolarisLogShipperRoleName() {
        return "cdktf-eks-fluent-bit-role-" + cluster;
    }

    public String getEbsCsiDriverRoleName() {
        return "cdktf-eks-ebs-csi-role-" + cluster;
    }
}
