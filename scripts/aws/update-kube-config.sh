source $AFON_ROOT/scripts/config/afon-source.sh aws

aws eks update-kubeconfig --name $CLUSTER_NAME --region $AWS_REGION