# Optionally given the stack serving KeyCloak, by its id or its role, eg: afon-cli.sh aws-keycloak [identity]
# Without it, the stack is found among the running stacks that deploy KeyCloak, by their EKS clusters
# <stack>-<CLUSTER_NAME>: as they all hold the DNS record of OIDC_DOMAIN_NAME, only one runs at a time.  The launcher
# gives them, from stacks.yaml, as AFON_STACK_CANDIDATES: the stacks with a KeyCloak, which those that deploy their own do not
# have if AWS_KEYCLOAK_EXTERNAL=true, as they then use the KeyCloak of another stack.
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/kube/keycloak-source.sh
source $AFON_ROOT/scripts/aws/status-source.sh

# Reports whether KeyCloak is up and serving on this env's AWS stack: login, VPC, EKS cluster, KeyCloak
# pod, ingress load balancer, then real HTTPS requests: its master realm and realm polaris, anonymously, and a
# token for the Polaris admin.
# No TLS secret check, as TLS ends at the ALB.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

status_require_domain OIDC_DOMAIN_NAME "${OIDC_DOMAIN_NAME:-}"
status_require aws kubectl curl

status_require_login AWS aws-login aws sts get-caller-identity

# a stack given by its id needs no lookup, but a role does, as <cloud>-<role> (which a stack defined under that id wins over) and
# as the role alone, as does finding the stacks that can serve Keycloak
case "$(echo "$STACK_ARG" | tr '[:upper:]' '[:lower:]')" in
  aws-identity|aws-catalog|aws-tre) NEEDS_STACK_LOOKUP=true ;;
  aws-*) NEEDS_STACK_LOOKUP= ;;
  *) NEEDS_STACK_LOOKUP=true ;;
esac
if [ -n "$NEEDS_STACK_LOOKUP" ]; then
  source $AFON_ROOT/scripts/stack/needs-source.sh
  stack_needs aws "${STACK_ARG:-none}" Keycloak || exit 1
  STACK_ARG="${AFON_STACK_ID:-}"
fi
aws_status_find_stack "$STACK_ARG" ${AFON_STACK_CANDIDATES:-}

echo "- Checking KeyCloak for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

aws_status_check_cluster
status_check_nodes
status_check_pod app=keycloak
status_check_ingress keycloak-ingress hostname "load balancer"
keycloak_check_https
keycloak_check_realm aws
keycloak_check_authenticated aws
status_report
