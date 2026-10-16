# Shared by the AWS status scripts, eg aws/polaris.sh and aws/keycloak.sh, which source it after the source of
# what they check, eg kube/polaris-source.sh.  Not run on its own.

# aws_status_find_stack <stack id given, or empty> <stack ids>...: sets STACK_CLUSTER_NAME, and so
# STATUS_CLUSTER_NAME, to the cluster <stack>-<CLUSTER_NAME> of the stack given, else of the one running among the
# stacks, found by its EKS cluster: as they hold a common exclusive resource, eg a domain name, only one of them runs at a
# time.  Reports FAILED if none runs, leaving STACK_CLUSTER_NAME empty, and could not verify if more than one does.
# The launcher gives the stack, or else the stacks that can serve what the script checks, see ScriptMain.
aws_status_find_stack() {
  local given="$1" ids types="" clusters found="" type cluster id
  shift
  ids="$*"
  for id in $ids; do
    types="$types ${id#aws-}"
  done
  types="${types# }"
  STACK_CLUSTER_NAME=
  if [ -n "$given" ]; then
    source $AFON_ROOT/scripts/aws/stack-source.sh "$given"
  else
    if ! clusters=$(aws eks list-clusters --region "$AWS_REGION" --query clusters --output text 2>"$STATUS_ERR"); then
      status_cannot_verify "- Could not list the EKS clusters in $AWS_REGION:"
    fi
    for type in $types; do
      for cluster in $clusters; do
        if [ "$cluster" == "$type-$CLUSTER_NAME" ]; then
          found="$found $cluster"
        fi
      done
    done
    case $(echo $found | wc -w | tr -d ' ') in
      0) status_failed "No stack serving $STATUS_SUBJECT found, ie no EKS cluster <stack>-$CLUSTER_NAME, for stacks: ${ids:-none}" ;;
      1) STACK_CLUSTER_NAME=$(echo $found) ;;
      *) status_cannot_verify "- More than one stack serving $STATUS_SUBJECT found:$found.  Give the stack as argument, by its id or its role, eg ${ids%% *}" ;;
    esac
  fi
  STATUS_CLUSTER_NAME="$STACK_CLUSTER_NAME"
}

# aws_status_check_cluster: the VPC and the EKS credentials of the cluster found, if any
aws_status_check_cluster() {
  if [ -z "$STATUS_CLUSTER_NAME" ]; then
    return 0
  fi
  local vpcs
  if vpcs=$(aws ec2 describe-vpcs --region "$AWS_REGION" --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-$STATUS_CLUSTER_NAME']].VpcId" --output text 2>"$STATUS_ERR"); then
    status_check_network "EKS VPC" "$vpcs"
  else
    status_cannot_verify "- Could not list the VPCs in $AWS_REGION:"
  fi
  status_get_credentials EKS "ResourceNotFound" \
    aws eks update-kubeconfig --name "$STATUS_CLUSTER_NAME" --region "$AWS_REGION" --kubeconfig "$STATUS_KUBECONFIG"
}
