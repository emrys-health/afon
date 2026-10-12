# Optionally given the type of the stack serving KeyCloak, eg: afon-cli.sh aws-keycloak [keycloak]
# Without it, the stack is found among the running stacks of the types serving KeyCloak, by their EKS clusters
# <stack-type>-<CLUSTER_NAME>: as they all hold the DNS record of OIDC_DOMAIN_NAME, only one runs at a time.  These
# are aws-keycloak, and aws-full and the stacks it extends, unless AWS_KEYCLOAK_EXTERNAL=true, as they then use the
# KeyCloak aws-keycloak deploys.
STACK_TYPE_ARG="${1:-}"
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

KEYCLOAK_STACK_TYPES="keycloak full spark jupyter"
if [ "$(echo "${AWS_KEYCLOAK_EXTERNAL:-}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')" == "true" ]; then
  KEYCLOAK_STACK_TYPES="keycloak"
fi
aws_status_find_stack "$STACK_TYPE_ARG" $KEYCLOAK_STACK_TYPES

echo "- Checking KeyCloak for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

aws_status_check_cluster
status_check_nodes
status_check_pod app=keycloak
status_check_ingress keycloak-ingress hostname "load balancer"
keycloak_check_https
keycloak_check_realm aws
keycloak_check_authenticated aws
status_report
