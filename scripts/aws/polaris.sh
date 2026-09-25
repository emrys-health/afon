source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/kube/polaris-source.sh

# Reports whether Polaris is up and serving on this env's AWS stack: login, VPC, EKS cluster, Polaris
# pod, ingress load balancer, then real HTTPS requests, anonymous and as the Polaris admin.
# No TLS secret check, as TLS ends at the ALB.
# See kube/polaris-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

polaris_require_domain AWS_POLARIS_DOMAIN_NAME "$AWS_POLARIS_DOMAIN_NAME"
polaris_require aws kubectl curl

echo "- Checking Polaris for cluster '$CLUSTER_NAME' at $POLARIS_DOMAIN"

polaris_require_login AWS aws-login aws sts get-caller-identity

if VPCS=$(aws ec2 describe-vpcs --region "$AWS_REGION" --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-$CLUSTER_NAME']].VpcId" --output text 2>"$POLARIS_ERR"); then
  polaris_check_network "EKS VPC" "$VPCS"
else
  polaris_cannot_verify "- Could not list the VPCs in $AWS_REGION:"
fi

polaris_get_credentials EKS "ResourceNotFound" \
  aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION" --kubeconfig "$POLARIS_KUBECONFIG"

polaris_check_nodes
polaris_check_pod
polaris_check_ingress hostname "load balancer"
polaris_check_https
polaris_check_authenticated aws
polaris_report
