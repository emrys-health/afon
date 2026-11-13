#!/usr/bin/env bash
# Writes the kubeconfig of the EKS cluster of the given stack, $AFON_CDKTF/kube/<stack-id>.config, not ~/.kube/config, by its id or its role (identity, catalog or tre), eg:
# aws-update-kube-config tre
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/aws/stack-source.sh "$STACK_ARG"

source $AFON_ROOT/scripts/kube/kubeconfig-source.sh
KUBECONFIG_FILE=$(kubeconfig_path "$STACK_ID")
mkdir -p "$(dirname "$KUBECONFIG_FILE")"
aws eks update-kubeconfig --name $STACK_CLUSTER_NAME --region $AWS_REGION --kubeconfig "$KUBECONFIG_FILE"
kubeconfig_current "$STACK_ID"
echo "kubeconfig of $STACK_ID: export KUBECONFIG=$KUBECONFIG_FILE"
