# Given the stack, by its id or its role (identity, catalog or tre), eg: afon-cli.sh gcp-cluster tre
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh gcp
source $AFON_ROOT/scripts/gcp/stack-source.sh "$STACK_ARG"

GCP_PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"
GCP_REGION="${GCP_REGION:?GCP_REGION must be set in afon-gcp.properties}"

# Checks whether the GKE cluster of the given stack for given env is up, by asking the cluster
# itself: can kubectl reach <stack>-$CLUSTER_NAME?  The tofu state is deliberately not read, as it
# may belong to a stack that someone else brought up (or that this env's $AFON_CDKTF never knew about).
# Only the cluster is checked, not the other resources of the stack (network, NAT, DNS records ...).
# 'gcloud container clusters describe' is what locates the cluster, and it also tells a cluster that does not exist
# (404) from one we cannot ask about (expired session, no permissions, no network).  Uses a throwaway
# kubeconfig, so your current kubectl context is left alone.
# Reports the status, so 'up' and 'down' are both a successful run: exit code 0 when the status was
# established (the cluster is up: kubectl reaches it, or down: no such cluster), 2 when it could not be
# verified, and 1 when no stack is given.  Never reported as down unless proven, so an expired Google session can't be mistaken for a
# clean teardown.

CLUSTER_KUBECONFIG=$(mktemp)
CLUSTER_ERR=$(mktemp)
cluster_kubectl() { kubectl --kubeconfig "$CLUSTER_KUBECONFIG" --request-timeout=20s "$@"; }
cluster_exit() { rm -f "$CLUSTER_KUBECONFIG" "$CLUSTER_ERR"; exit "$1"; }

for CLUSTER_TOOL in gcloud kubectl gke-gcloud-auth-plugin; do
  if ! command -v "$CLUSTER_TOOL" >/dev/null 2>&1; then
    echo "$CLUSTER_TOOL not found, cannot verify the cluster state (install the tools with: afon-cli.sh local-setup -c gcp)"
    cluster_exit 2
  fi
done

echo "- Checking the GKE cluster '$STACK_CLUSTER_NAME' (project $GCP_PROJECT_ID, region $GCP_REGION) via kubectl"

if gcloud container clusters describe "$STACK_CLUSTER_NAME" --region "$GCP_REGION" --project "$GCP_PROJECT_ID" --format="value(status)" >/dev/null 2>"$CLUSTER_ERR"; then
  echo "- GKE cluster '$STACK_CLUSTER_NAME' found"
elif grep -q "code=404\|NOT_FOUND\|Not found\|was not found" "$CLUSTER_ERR"; then
  echo "- No GKE cluster '$STACK_CLUSTER_NAME' in project $GCP_PROJECT_ID, region $GCP_REGION"
  echo "Cluster is down."
  cluster_exit 0
else
  echo "- Could not look up GKE cluster '$STACK_CLUSTER_NAME':"
  cat "$CLUSTER_ERR"
  cluster_exit 2
fi

# The credentials go to a kubeconfig of their own, which names gke-gcloud-auth-plugin to log in with the gcloud account
if ! KUBECONFIG="$CLUSTER_KUBECONFIG" gcloud container clusters get-credentials "$STACK_CLUSTER_NAME" --region "$GCP_REGION" --project "$GCP_PROJECT_ID" >/dev/null 2>"$CLUSTER_ERR"; then
  echo "- Could not get the kubeconfig of GKE cluster '$STACK_CLUSTER_NAME':"
  cat "$CLUSTER_ERR"
  cluster_exit 2
fi

# The API server's own readiness, which needs no RBAC rights, so 'up' does not depend on who is asking.
if cluster_kubectl get --raw /readyz >/dev/null 2>"$CLUSTER_ERR"; then
  # Listing nodes needs more rights than /readyz, and is only there for information.
  if CLUSTER_NODES=$(cluster_kubectl get nodes -o name 2>/dev/null); then
    CLUSTER_NODES_INFO="$(echo "$CLUSTER_NODES" | grep -c . || true) node(s)"
  else
    CLUSTER_NODES_INFO="node count unavailable"
  fi
  echo "- Up: kubectl reaches the cluster, whose API server is ready ($CLUSTER_NODES_INFO)"
  echo "Cluster is up."
  cluster_exit 0
fi

# The cluster exists but its API server does not answer: being created, updated or deleted, or not reachable
# from here.  Neither up nor down, so say what is known.
CLUSTER_STATUS=$(gcloud container clusters describe "$STACK_CLUSTER_NAME" --region "$GCP_REGION" --project "$GCP_PROJECT_ID" --format="value(status)" 2>/dev/null || true)
echo "- GKE cluster '$STACK_CLUSTER_NAME' exists (status ${CLUSTER_STATUS:-unknown}) but kubectl cannot reach it:"
cat "$CLUSTER_ERR"
echo "Could not verify the cluster state."
cluster_exit 2
