# Optionally given the stack serving KeyCloak, by its id or its role, eg: afon-cli.sh azure-keycloak [identity]
# Without it, the stack is found among the running stacks that deploy KeyCloak, by their AKS clusters
# <stack>-<CLUSTER_NAME>: as they all hold the DNS record of OIDC_DOMAIN_NAME, only one runs at a time.  The launcher
# gives them, from stacks.yaml, as AFON_STACK_CANDIDATES: the stacks with a KeyCloak, which those that deploy their own do not
# have if AZURE_KEYCLOAK_EXTERNAL=true, as they then use the KeyCloak of another stack.
STACK_ARG="${1:-}"
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

# a stack given by its id needs no lookup, but a role does, as <cloud>-<role> (which a stack defined under that id wins over) and
# as the role alone, as does finding the stacks that can serve Keycloak
case "$(echo "$STACK_ARG" | tr '[:upper:]' '[:lower:]')" in
  azure-identity|azure-catalog|azure-tre) NEEDS_STACK_LOOKUP=true ;;
  azure-*) NEEDS_STACK_LOOKUP= ;;
  *) NEEDS_STACK_LOOKUP=true ;;
esac
if [ -n "$NEEDS_STACK_LOOKUP" ]; then
  source $AFON_ROOT/scripts/stack/needs-source.sh
  stack_needs azure "${STACK_ARG:-none}" Keycloak || exit 1
  STACK_ARG="${AFON_STACK_ID:-}"
fi
azure_status_find_stack "$STACK_ARG" ${AFON_STACK_CANDIDATES:-}

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
