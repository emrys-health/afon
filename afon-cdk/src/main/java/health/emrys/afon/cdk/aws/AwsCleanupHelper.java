package health.emrys.afon.cdk.aws;

import health.emrys.afon.cdk.AbstractCleanupHelper;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;

import java.util.Properties;

import static health.emrys.afon.cdk.CdkHelper.*;
import static java.lang.String.format;

public class AwsCleanupHelper extends AbstractCleanupHelper {

    private AwsComponentNames componentNames;

    public AwsCleanupHelper() {
        this(new AfonPropertiesLoader().getProperties());
    }

    AwsCleanupHelper(Properties properties) {
        componentNames = new AwsComponentNames(properties);
    }

    /**
     * Destroy-time cleanup (a {@code local-exec} on the public subnet) that removes the
     * aws-load-balancer-controller's orphaned AWS resources <em>before</em> Terraform deletes the
     * subnets. The controller is uninstalled on destroy before it can delete its own ELBs, so those
     * ELBs (and their RequesterManaged ENIs, security groups and target groups) are left behind and
     * their ENIs pin the subnets — {@code DeleteSubnet} then fails with {@code DependencyViolation}
     * and retries for ~20 min. This deletes the ELBs and, critically, <em>waits</em> for the delete
     * to complete so the ENIs are released before the subnet delete proceeds. It is deliberately
     * loud (echoes the resolved VPC and a stderr warning if it cannot be resolved) so a future
     * failure is visible in the tofu output rather than silently no-ops into a 20 min hang.
     */
    @NonNull String deleteLoadBalancersCommand() {
        return format(
            """
            VPCS=$(%s)
            if [ -z "$VPCS" ]; then
              echo "cleanELBs: could NOT resolve any VPC with Name tag '%s' - skipping ELB cleanup; a subnet DependencyViolation hang is likely" >&2
              exit 0
            fi
            # A failed prior destroy can leave several VPCs sharing the same Name tag. describe-vpcs then
            # returns multiple ids; iterate (rather than 'export VPC=$(...)', which yields a multi-value
            # string that matches no single VpcId and silently no-ops the whole cleanup).
            for VPC in $VPCS ; do
              echo "cleanupELBs: removing load-balancer-controller resources in $VPC"
              LBS=$(aws elbv2 describe-load-balancers --query "LoadBalancers[?VpcId=='$VPC'].LoadBalancerArn" --output text)
              for LB in $LBS ; do
                echo "Deleting load balancer $LB"
                aws elbv2 delete-load-balancer --load-balancer-arn "$LB" || true
              done
              # Block until every LB is really gone. delete-load-balancer is async and its (RequesterManaged)
              # ENIs linger for ~30-60s after it returns; without this wait Terraform races ahead to
              # DeleteSubnet and hits DependencyViolation. The waiter polls for up to ~10 min per LB (they
              # delete concurrently, so later waits return quickly).
              for LB in $LBS ; do
                echo "Waiting for $LB to finish deleting"
                aws elbv2 wait load-balancers-deleted --load-balancer-arns "$LB" 2>/dev/null || true
              done
              # Target groups and the k8s-* security groups the controller created are not in Terraform
              # state; delete the orphans so the later security-group / VPC destroy is not blocked either.
              for TG in $(aws elbv2 describe-target-groups --query "TargetGroups[?VpcId=='$VPC'].TargetGroupArn" --output text) ; do
                echo "Deleting target group $TG"
                aws elbv2 delete-target-group --target-group-arn "$TG" || true
              done
              for SG in $(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC" --query "SecurityGroups[?starts_with(GroupName, 'k8s-')].GroupId" --output text) ; do
                echo "Deleting LB-controller security group $SG"
                aws ec2 delete-security-group --group-id "$SG" || true
              done
              # Belt-and-braces: drop any now-detached ENIs the LB deletion left behind (available = unattached).
              for ENI in $(aws ec2 describe-network-interfaces --filters "Name=vpc-id,Values=$VPC" "Name=status,Values=available" --query "NetworkInterfaces[].NetworkInterfaceId" --output text) ; do
                echo "Deleting leftover ENI $ENI"
                aws ec2 delete-network-interface --network-interface-id "$ENI" 2>/dev/null || true
              done
            done
            """,
            listVPCsCommand(), componentNames.getVpcId());
    }

    public @NonNull String deleteSecurityGroupsCommand() {
        String command = String.format(
                """
                        echo "Destroying security-groups"
                        for SG_ID in $(aws ec2 describe-security-groups --filters Name=vpc-id,Values=%s --region %s --query 'SecurityGroups[*].[GroupId]' --output text --no-cli-pager); do { 
                          echo $SG_ID ;  
                          aws ec2 delete-security-group --group-id $SG_ID || true 
                        } done""", "$VPC", componentNames.getRegion());
        return format(
                """
                export VPC=$(%s)
                %s
                """,
                listVPCsCommand(),
                command);
    }

    private @NonNull String deleteClusterCommand() {
        return format("eksctl delete cluster --name %s --region %s", componentNames.getCluster(), componentNames.getRegion());
    }

    private @NonNull String listVPCsCommand() {
        return format(
                """
                aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='%s']].VpcId"   --output text""",
                componentNames.getVpcId());
    }

    public @NonNull String cleanupCommands() {
        return format(
            """
            # Note that EKS depends on the subnets.
            # Once the EKS cluster is deleted, you can delete the subnets.

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
            
            aws --region %s secretsmanager  delete-secret --secret-id %s --force-delete-without-recovery
            
            """,
            listVPCsCommand(),
            NO_POLARIS_FINALIZERS,
            NO_JUPYTER_FINALIZERS,
            NO_JUPYTER_LB_FINALIZERS,
            NO_SPARK_LB_FINALIZERS,
            deleteClusterCommand(),
            componentNames.getClusterRoleName(),
            componentNames.getNodeGroupRoleName(),
            componentNames.getLoadBalancerRoleName(),
            componentNames.getAccessPolicyName(),
            componentNames.getRegion(),
            componentNames.getSecret()
                     );
    }

    public static void main(String[] args) {
        outputCleanupCommands(args, new AwsCleanupHelper().cleanupCommands());
    }


}
