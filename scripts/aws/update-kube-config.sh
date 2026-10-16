# Points kubectl at the EKS cluster of the given stack, by its id or its role (identity, catalog or tre), eg:
# afon-cli.sh aws-update-kube-config tre
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/aws/stack-source.sh "$STACK_ARG"

aws eks update-kubeconfig --name $STACK_CLUSTER_NAME --region $AWS_REGION
