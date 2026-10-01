# Optionally given the type of the stack serving Polaris, eg: afon-cli.sh aws-polaris [polaris]
# Without it, the stack is found among the running stacks of the types serving Polaris, by their EKS clusters
# <stack-type>-<CLUSTER_NAME>: as they all hold the DNS record of AWS_POLARIS_DOMAIN_NAME, only one runs at a time.
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/kube/polaris-source.sh
source $AFON_ROOT/scripts/aws/status-source.sh

# Reports whether Polaris is up and serving on this env's AWS stack: login, VPC, EKS cluster, Polaris
# pod, ingress load balancer, then real HTTPS requests, anonymous and as the Polaris admin.
# No TLS secret check, as TLS ends at the ALB.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

status_require_domain AWS_POLARIS_DOMAIN_NAME "$AWS_POLARIS_DOMAIN_NAME"
status_require aws kubectl curl

status_require_login AWS aws-login aws sts get-caller-identity

aws_status_find_stack "$STACK_TYPE_ARG" polaris full spark jupyter

echo "- Checking Polaris for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

aws_status_check_cluster
status_check_nodes
status_check_pod app=polaris
status_check_ingress polaris-ingress hostname "load balancer"
polaris_check_https
polaris_check_authenticated aws
status_report
