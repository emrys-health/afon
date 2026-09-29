# Optionally given the type of the stack serving Polaris, eg: afon-cli.sh aws-polaris [polaris]
# Without it, the stack is found among the running stacks of the types serving Polaris, by their EKS clusters
# <stack-type>-<CLUSTER_NAME>: as they all hold the DNS record of AWS_POLARIS_DOMAIN_NAME, only one runs at a time.
POLARIS_STACK_TYPES="polaris full spark jupyter"
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
if [ -n "$STACK_TYPE_ARG" ]; then
  source $AFON_ROOT/scripts/aws/stack-source.sh "$STACK_TYPE_ARG"
fi
source $AFON_ROOT/scripts/kube/polaris-source.sh

# Reports whether Polaris is up and serving on this env's AWS stack: login, VPC, EKS cluster, Polaris
# pod, ingress load balancer, then real HTTPS requests, anonymous and as the Polaris admin.
# No TLS secret check, as TLS ends at the ALB.
# See kube/polaris-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

polaris_require_domain AWS_POLARIS_DOMAIN_NAME "$AWS_POLARIS_DOMAIN_NAME"
polaris_require aws kubectl curl

polaris_require_login AWS aws-login aws sts get-caller-identity

if [ -z "$STACK_TYPE_ARG" ]; then
  if ! CLUSTERS=$(aws eks list-clusters --region "$AWS_REGION" --query clusters --output text 2>"$POLARIS_ERR"); then
    polaris_cannot_verify "- Could not list the EKS clusters in $AWS_REGION:"
  fi
  POLARIS_STACK_CLUSTERS=
  for POLARIS_STACK_TYPE in $POLARIS_STACK_TYPES; do
    for CLUSTER in $CLUSTERS; do
      if [ "$CLUSTER" == "$POLARIS_STACK_TYPE-$CLUSTER_NAME" ]; then
        POLARIS_STACK_CLUSTERS="$POLARIS_STACK_CLUSTERS $CLUSTER"
      fi
    done
  done
  case $(echo $POLARIS_STACK_CLUSTERS | wc -w | tr -d ' ') in
    0) polaris_failed "No stack serving Polaris found, ie no EKS cluster <stack-type>-$CLUSTER_NAME, for stack types: $POLARIS_STACK_TYPES" ;;
    1) STACK_CLUSTER_NAME=$(echo $POLARIS_STACK_CLUSTERS) ;;
    *) polaris_cannot_verify "- More than one stack serving Polaris found:$POLARIS_STACK_CLUSTERS.  Give the stack type, eg: afon-cli.sh aws-polaris polaris" ;;
  esac
fi
POLARIS_CLUSTER_NAME="$STACK_CLUSTER_NAME"

echo "- Checking Polaris for cluster '${STACK_CLUSTER_NAME:-none}' at $POLARIS_DOMAIN"

if [ -n "$STACK_CLUSTER_NAME" ]; then
  if VPCS=$(aws ec2 describe-vpcs --region "$AWS_REGION" --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-$STACK_CLUSTER_NAME']].VpcId" --output text 2>"$POLARIS_ERR"); then
    polaris_check_network "EKS VPC" "$VPCS"
  else
    polaris_cannot_verify "- Could not list the VPCs in $AWS_REGION:"
  fi

  polaris_get_credentials EKS "ResourceNotFound" \
    aws eks update-kubeconfig --name "$STACK_CLUSTER_NAME" --region "$AWS_REGION" --kubeconfig "$POLARIS_KUBECONFIG"
fi

polaris_check_nodes
polaris_check_pod
polaris_check_ingress hostname "load balancer"
polaris_check_https
polaris_check_authenticated aws
polaris_report
