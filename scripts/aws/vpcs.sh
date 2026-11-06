#!/usr/bin/env bash
# Lists the EKS VPCs of the given stack, by its id or its role (identity, catalog or tre), eg: afon-cli.sh aws-vpcs identity
# or, if no stack is given, of every stack of this env, ie named from $CLUSTER_NAME, qualified or not by the
# stack, ie its id without the cloud, the unqualified one being from before each stack had its own.  Note an env whose CLUSTER_NAME
# ends with '-' followed by this env's CLUSTER_NAME would have its VPCs listed too, hence the names are shown.
# Warns if several VPCs share a name, as left over from a failed deploy or destroy: the cleanup of a stack is by VPC name, and
# a deploy is refused while the stack has no VPC in its tofu state but one exists in the cloud.
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
  DUPLICATES=$(echo "$VPCS" | awk -F'\t' '{ids[$2]=ids[$2] " " $1; n[$2]++} END {for (name in n) if (n[name] > 1) print name ":" ids[name]}')
  if [ -n "$DUPLICATES" ]; then
    echo "WARNING: several VPCs share a name, ie one is left over from a failed deploy or destroy. Delete the extra ones:" >&2
    echo "$DUPLICATES" >&2
  fi
else
  echo "No VPCS running"
fi
