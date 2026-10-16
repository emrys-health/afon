# Optionally given the stack serving Polaris, by its id or its role, eg: afon-cli.sh aws-polaris [catalog]
# Without it, the stack is found among the running stacks that deploy Polaris, by their EKS clusters
# <stack>-<CLUSTER_NAME>: as they all hold the DNS record of AWS_POLARIS_DOMAIN_NAME, only one runs at a time.  The
# launcher gives them, from stacks.yaml, as AFON_STACK_CANDIDATES, or the ops command stack-needs if run by afon.sh.
STACK_ARG="${1:-}"
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

# a stack given by its id needs no lookup, but a role does, as <cloud>-<role> (which a stack defined under that id wins over) and
# as the role alone, as does finding the stacks that can serve Polaris
case "$(echo "$STACK_ARG" | tr '[:upper:]' '[:lower:]')" in
  aws-identity|aws-catalog|aws-tre) NEEDS_STACK_LOOKUP=true ;;
  aws-*) NEEDS_STACK_LOOKUP= ;;
  *) NEEDS_STACK_LOOKUP=true ;;
esac
if [ -n "$NEEDS_STACK_LOOKUP" ]; then
  source $AFON_ROOT/scripts/stack/needs-source.sh
  stack_needs aws "${STACK_ARG:-none}" Polaris || exit 1
  STACK_ARG="${AFON_STACK_ID:-}"
fi
aws_status_find_stack "$STACK_ARG" ${AFON_STACK_CANDIDATES:-}

echo "- Checking Polaris for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

aws_status_check_cluster
status_check_nodes
status_check_pod app=polaris
status_check_ingress polaris-ingress hostname "load balancer"
polaris_check_https
polaris_check_authenticated aws
status_report
