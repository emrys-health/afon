# Optionally given the stack serving Polaris, by its id or its role, eg: afon-cli.sh gcp-polaris [catalog]
# Without it, the stack is found among the running stacks that deploy Polaris, by their GKE clusters
# <stack>-<CLUSTER_NAME>: as they all hold the DNS record of GCP_POLARIS_DOMAIN_NAME, only one runs at a time.  The
# launcher gives them, from stacks.yaml, as AFON_STACK_CANDIDATES, or the ops command stack-needs if run by afon.sh.
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh gcp
source $AFON_ROOT/scripts/kube/polaris-source.sh
source $AFON_ROOT/scripts/gcp/status-source.sh

# Reports whether Polaris is up and serving on this env's GCP stack: login, GKE network, GKE cluster,
# Polaris pod, ingress external IP, TLS secret, then real HTTPS requests, anonymous and as the Polaris admin.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

GCP_PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"

status_require_domain GCP_POLARIS_DOMAIN_NAME "$GCP_POLARIS_DOMAIN_NAME"
status_require gcloud kubectl curl
if ! command -v gke-gcloud-auth-plugin >/dev/null 2>&1; then
  status_cannot_verify "gke-gcloud-auth-plugin not found, cannot verify Polaris (install it with: afon-cli.sh local-setup -c gcp)"
fi

status_require_login GCP gcp-login gcloud auth print-access-token

# a stack given by its id needs no lookup, but a role does, as <cloud>-<role> (which a stack defined under that id wins over) and
# as the role alone, as does finding the stacks that can serve Polaris
case "$(echo "$STACK_ARG" | tr '[:upper:]' '[:lower:]')" in
  gcp-identity|gcp-catalog|gcp-tre) NEEDS_STACK_LOOKUP=true ;;
  gcp-*) NEEDS_STACK_LOOKUP= ;;
  *) NEEDS_STACK_LOOKUP=true ;;
esac
if [ -n "$NEEDS_STACK_LOOKUP" ]; then
  source $AFON_ROOT/scripts/stack/needs-source.sh
  stack_needs gcp "${STACK_ARG:-none}" Polaris || exit 1
  STACK_ARG="${AFON_STACK_ID:-}"
fi
gcp_status_find_stack "$STACK_ARG" ${AFON_STACK_CANDIDATES:-}

echo "- Checking Polaris for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

gcp_status_check_cluster
status_check_nodes
status_check_pod app=polaris
status_check_ingress polaris-ingress ip "external IP"
status_check_tls polaris-tls
polaris_check_https
polaris_check_authenticated gcp
status_report
