# Optionally given the type of the stack serving KeyCloak, eg: afon-cli.sh azure-keycloak [keycloak]
# Without it, the stack is found among the running stacks of the types serving KeyCloak, by their AKS clusters
# <stack-type>-<CLUSTER_NAME>: as they all hold the DNS record of OIDC_DOMAIN_NAME, only one runs at a time.  These
# are azure-keycloak, and azure-full unless AZURE_KEYCLOAK_EXTERNAL=true, as it then uses the KeyCloak azure-keycloak deploys.
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/kube/keycloak-source.sh
source $AFON_ROOT/scripts/azure/status-source.sh

# Reports whether KeyCloak is up and serving on this env's Azure stack: login, AKS network, AKS cluster, KeyCloak
# pod, ingress external IP, TLS secret, then real HTTPS requests: its master realm and realm polaris, anonymously, and a
# token for the Polaris admin.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

status_require_domain OIDC_DOMAIN_NAME "${OIDC_DOMAIN_NAME:-}"
status_require az kubectl kubelogin curl

status_require_login Azure azure-login az account get-access-token --output none

KEYCLOAK_STACK_TYPES="keycloak full"
if [ "$(echo "${AZURE_KEYCLOAK_EXTERNAL:-}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')" == "true" ]; then
  KEYCLOAK_STACK_TYPES="keycloak"
fi
azure_status_find_stack "$STACK_TYPE_ARG" $KEYCLOAK_STACK_TYPES

echo "- Checking KeyCloak for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

azure_status_check_cluster
status_check_nodes
status_check_pod app=keycloak
status_check_ingress keycloak-ingress ip "external IP"
status_check_tls keycloak-tls
keycloak_check_https
keycloak_check_realm azure
keycloak_check_authenticated azure
status_report
