# Lists the EKS VPCs of the given stack, by its id or its role (identity, catalog or tre), eg: afon-cli.sh aws-vpcs identity
# or, if no stack is given, of every stack of this env, ie named from $CLUSTER_NAME, qualified or not by the
# stack, ie its id without the cloud, the unqualified one being from before each stack had its own.  Note an env whose CLUSTER_NAME
# ends with '-' followed by this env's CLUSTER_NAME would have its VPCs listed too, hence the names are shown.
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws

VPC_NAME_PREFIX="cdktf-eks-vpc-"
if [ -n "$STACK_ARG" ]; then
  source $AFON_ROOT/scripts/aws/stack-source.sh "$STACK_ARG"
  VPC_NAME_FILTER="Value=='$VPC_NAME_PREFIX$STACK_CLUSTER_NAME'"
else
  VPC_NAME_FILTER="(Value=='$VPC_NAME_PREFIX$CLUSTER_NAME' || (starts_with(Value, '$VPC_NAME_PREFIX') && ends_with(Value, '-$CLUSTER_NAME')))"
fi
VPCS=$(aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && $VPC_NAME_FILTER]].[VpcId, Tags[?Key=='Name'].Value | [0]]" --output text)
if [ -n "$VPCS" ]; then
  echo "VPCS running:"
  echo "$VPCS"
else
  echo "No VPCS running"
fi
