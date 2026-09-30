# Sourced by the AWS scripts acting on the stack of a given type, given as $1, eg keycloak for stack aws-keycloak.
# Sets STACK_TYPE and STACK_CLUSTER_NAME, ie the cluster name <stack-type>-<CLUSTER_NAME> from which the
# components of the stack are named, as by AwsComponentNames, eg its EKS cluster and its VPC cdktf-eks-vpc-<cluster-name>.
STACK_TYPE="${1:-}"
STACK_TYPE="${STACK_TYPE#aws-}"
if [ -z "$STACK_TYPE" ]; then
  echo "Requires argument <stack-type>, eg keycloak, polaris or full, for stack aws-<stack-type>"
  exit 1
fi
if [ -z "${CLUSTER_NAME:-}" ]; then
  echo "CLUSTER_NAME is not set, cannot name the cluster of stack aws-$STACK_TYPE"
  exit 1
fi
STACK_CLUSTER_NAME="$STACK_TYPE-$CLUSTER_NAME"
