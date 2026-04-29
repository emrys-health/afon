package health.emrys.afon.cdk;

import com.hashicorp.cdktf.LocalExecProvisioner;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.NO_JUPYTER_LB;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.PolarisConfig.NO_JUPYTER_FINALIZERS;
import static health.emrys.afon.cdk.PolarisConfig.NO_POLARIS_FINALIZERS;
import static health.emrys.afon.cdk.SparkClusterDeployment.NO_SPARK_LB_FINALIZERS;
import static health.emrys.afon.config.AfonProperties.CLUSTER_NAME;
import static java.lang.String.format;

public class AwsNuke {

    /**
     * Note that EKS depends on the subnets.
     * Once the EKS is nuked with "eksctl delete cluster", you can delete the subnets with:
     * aws ec2 delete-subnet --subnet-id subnet-XXX
     * then delete the whole VPC
     */
    public static void main(String[] args) {
        var app = new AwsNuke();
        System.out.println("\u001B[31m" + app.cleanUpHint() + "\u001B[0m");
    }

    private String clusterName;
    private AwsComponentNames componentNames;

    public AwsNuke() {
        this(new AfonPropertiesLoader().getProperties()); // TODO get some of the values from here
    }

    AwsNuke(Properties properties) {
        clusterName = CLUSTER_NAME.getValueFrom(properties);
        componentNames = new AwsComponentNames(properties);
    }

    @NotNull String cleanSecurityGroupsCommand(String vpcId) {
        String region = "eu-west-2"; // TODO make configuratble
        return format(
            """
            echo "Destroying security-groups"
            for SG_ID in $(aws ec2 describe-security-groups --filters Name=vpc-id,Values=%s --region %s --query 'SecurityGroups[*].[GroupId]' --output text --no-cli-pager); do { 
              echo $SG_ID ;  
              aws ec2 delete-security-group --group-id $SG_ID || true 
            } done""", vpcId, region);
    }

    @NotNull String cleanELBs() {
        return format(
            """
            %s
            for LB in $(aws elbv2 describe-load-balancers --query "LoadBalancers[?VpcId=='$VPC'].LoadBalancerArn" --output text) ; do
              echo "Deleting LB $LB"
              aws elbv2 delete-load-balancer --load-balancer-arn $LB
            done
            for ENI in $(aws ec2 describe-network-interfaces --filters "Name=vpc-id,Values=$VPC" --query "NetworkInterfaces[?RequesterManaged==\\`false\\`].NetworkInterfaceId" --output text) ; do
              ATTACHMENT=$(aws ec2 describe-network-interfaces --network-interface-ids $ENI --query "NetworkInterfaces[0].Attachment.AttachmentId" --output text 2>/dev/null)
              if [ -n "$ATTACHMENT" ] && [ "$ATTACHMENT" != "None" ] && [ "$ATTACHMENT" != "null" ]; then
                echo "Force-detaching $ENI (attachment $ATTACHMENT)"
                aws ec2 detach-network-interface --attachment-id $ATTACHMENT --force 2>/dev/null || true
                aws ec2 wait network-interface-available --network-interface-ids $ENI 2>/dev/null || true
              fi
              echo "Deleting ENI $ENI"
              aws ec2 delete-network-interface --network-interface-id $ENI 2>/dev/null || true
            done
            """,
            getVPCCommand());
    }

    @NotNull String getVPCCommand() {
        return format("export VPC=$(%s)", getVPCsCommand());
    }

    @NotNull String getVPCsCommand() {
        return format(
            """
            aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='%s']].VpcId"   --output text""",
            componentNames.getVpcId());
    }

    @NotNull String cleanEksCommand() {
        return format("eksctl delete cluster --name %s --region eu-west-2", clusterName);
    }

    public @NonNull String cleanUpHint() {
        return format(
            """
            
            unalias aws
            
            for VPC_ID in $(%s) ; do {
               echo Destroying $VPC_ID
               echo destroying service and ingress...
                %s
                %s
                %s
                %s
                %s
                for LB in $(aws elbv2 describe-load-balancers  --query "LoadBalancers[?VpcId=='$VPC_ID'].LoadBalancerArn" --output text) ; do {
                  echo $LB
                  aws elbv2 delete-load-balancer  --load-balancer-arn $LB
                } done
                echo destroying internet gateways...
                for igw in $(aws ec2 describe-internet-gateways --filters Name=attachment.vpc-id,Values=$VPC_ID --query 'InternetGateways[].InternetGatewayId' --output text); do { 
                  echo $igw
                  aws ec2 detach-internet-gateway --internet-gateway-id $igw --vpc-id $VPC_ID; 
                  aws ec2 delete-internet-gateway --internet-gateway-id $igw; 
                } done; 
                echo VPC endpoints...
                aws ec2 describe-vpc-endpoints --filters Name=vpc-id,Values=$VPC_ID --query 'VpcEndpoints[].VpcEndpointId' --output text | \\ 
                  xargs -r aws ec2 delete-vpc-endpoints --vpc-endpoint-ids; 
                echo NAT gateways...
                aws ec2 describe-nat-gateways --filter Name=vpc-id,Values=$VPC_ID --query 'NatGateways[].NatGatewayId' --output text | \\
                  xargs -r -n1 aws ec2 delete-nat-gateway --nat-gateway-id; 
                echo network interfaces...
                aws ec2 describe-network-interfaces --filters Name=vpc-id,Values=$VPC_ID --query 'NetworkInterfaces[].NetworkInterfaceId' --output text | \\ 
                  xargs -r -n1 aws ec2 delete-network-interface --network-interface-id;
                echo ELBs
                for SUBNET_ID in $(aws ec2 describe-subnets --filters Name=vpc-id,Values=$VPC_ID  --query 'Subnets[].{Id:SubnetId}' --output text) ; do { 
                  for ELB in $(aws elb describe-load-balancers --query 'LoadBalancerDescriptions[?Subnets.contains(@, `'"$SUBNET_ID"'`)].LoadBalancerName' --output text) ; do {
                    echo $ELB
                    aws elb delete-load-balancer --load-balancer-name  $ELB
                  } done
                } done   
                echo subnets...
                for subnet in $(aws ec2 describe-subnets --filters Name=vpc-id,Values=$VPC_ID --query 'Subnets[].SubnetId' --output text); do {
                  echo $SUBNET_ID ; 
                  aws ec2 delete-subnet --subnet-id $subnet; 
                } done; 
                echo routes tables...
                for rtb in $(aws ec2 describe-route-tables --filters Name=vpc-id,Values=$VPC_ID --query 'RouteTables[].RouteTableId' --output text); do {
                  for ASSOC in $(aws ec2 describe-route-tables --route-table-ids $rtb --query "RouteTables[0].Associations[0].RouteTableAssociationId" --output text); do {
                    echo $rdb $ASSOC
                    aws ec2 disassociate-route-table   --association-id $ASSOC ;
                  } done
                  if ! aws ec2 describe-route-tables --route-table-ids $rtb --query 'RouteTables[].Associations[?Main].Main' --output text | grep -q True; then
                    echo $rdb 
                    aws ec2 delete-route-table --route-table-id $rtb; 
                  fi; 
                } done; 
                echo security groups...
                for sg in $(aws ec2 describe-security-groups --filters Name=vpc-id,Values=$VPC_ID --query 'SecurityGroups[?GroupName!=`default`].GroupId' --output text); do {
                  echo $sg 
                  aws ec2 delete-security-group --group-id $sg; 
                } done; 
                echo network ACLs
                for nacl in $(aws ec2 describe-network-acls --filters Name=vpc-id,Values=$VPC_ID --query 'NetworkAcls[?IsDefault==`false`].NetworkAclId' --output text); do {
                  echo $nacl 
                  aws ec2 delete-network-acl --network-acl-id $nacl; 
                } done; 
                echo and the VPC
                aws ec2 delete-vpc --vpc-id $VPC_ID
            } done
            
            for ROLE_NAME in $(echo "%s" "%s" "%s") ; do {
               echo $ROLE_NAME ;
               for ARN in $(aws iam list-attached-role-policies --role-name $ROLE_NAME --query 'AttachedPolicies[*].PolicyArn' --output text) ; do {
                   echo "Detaching $ROLE_NAME from $ARN"
                   aws iam detach-role-policy --role-name $ROLE_NAME --policy-arn $ARN
               } done
               for PROFILE in $(aws iam list-instance-profiles-for-role --query 'AttachedPolicies[*].PolicyArn' --role-name $ROLE_NAME --query "InstanceProfiles[].InstanceProfileName"   --output text) ; do {
                   echo "removing $ROLE_NAME from $PROFILE"
                   aws iam remove-role-from-instance-profile --instance-profile-name $PROFILE --role-name $ROLE_NAME
               } done
               aws iam delete-role --role-name  $ROLE_NAME
            } done
            
            aws iam delete-policy --policy-arn arn:aws:iam::418295715997:policy/%s
            
            aws --region eu-west-2 secretsmanager  delete-secret --secret-id %s --force-delete-without-recovery
            
            """,
            getVPCsCommand(),
            NO_POLARIS_FINALIZERS,
            NO_JUPYTER_FINALIZERS,
            NO_JUPYTER_LB,
            NO_SPARK_LB_FINALIZERS,
            cleanEksCommand(),
            componentNames.getClusterRoleName(),
            componentNames.getNodeGroupRoleName(),
            componentNames.getLoadBalancerRoleName(),
            componentNames.getAccessPolicyName(),
            componentNames.getSecret()
                     );
    }

    public @NotNull List<LocalExecProvisioner> destroySGsAndVPC() {
        return uponDestroyRun(format(
            """
            %s
            %s
            """,
            getVPCCommand(),
            cleanSecurityGroupsCommand("$VPC")));
    }

}
