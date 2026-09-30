# Points kubectl at the EKS cluster of the stack of the given type, eg: afon-cli.sh aws-update-kube-config keycloak
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/aws/stack-source.sh "$STACK_TYPE_ARG"

aws eks update-kubeconfig --name $STACK_CLUSTER_NAME --region $AWS_REGION
