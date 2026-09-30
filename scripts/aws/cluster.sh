# Given the stack type, eg: afon-cli.sh aws-cluster keycloak
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/aws/stack-source.sh "$STACK_TYPE_ARG"

# Checks whether the EKS cluster of the stack of given type for given env is up, by asking the cluster
# itself: can kubectl reach <stack-type>-$CLUSTER_NAME?  The tofu state is deliberately not read, as it
# may belong to a stack that someone else brought up (or that this env's $AFON_CDKTF never knew about).
# Only the cluster is checked, not the other resources of the stack (VPC, NAT EIP, IAM ...).
# 'aws eks update-kubeconfig' is what locates the cluster for kubectl, and it also tells a cluster that
# does not exist (ResourceNotFoundException) from one we cannot ask about (expired session, no
# permissions, no network).  Uses a throwaway kubeconfig, so your current kubectl context is left alone.
# Reports the status, so 'up' and 'down' are both a successful run: exit code 0 when the status was
# established (the cluster is up: kubectl reaches it, or down: no such cluster), 2 when it could not be
# verified, and 1 when no stack type is given.  Never reported as down unless proven, so an expired AWS session can't be mistaken for a
# clean teardown.

CLUSTER_KUBECONFIG=$(mktemp)
CLUSTER_ERR=$(mktemp)
cluster_kubectl() { kubectl --kubeconfig "$CLUSTER_KUBECONFIG" --request-timeout=20s "$@"; }
cluster_exit() { rm -f "$CLUSTER_KUBECONFIG" "$CLUSTER_ERR"; exit "$1"; }

for CLUSTER_TOOL in aws kubectl; do
  if ! command -v "$CLUSTER_TOOL" >/dev/null 2>&1; then
    echo "$CLUSTER_TOOL not found, cannot verify the cluster state"
    cluster_exit 2
  fi
done

echo "- Checking the EKS cluster '$STACK_CLUSTER_NAME' (region $AWS_REGION) via kubectl"

if aws eks update-kubeconfig --name "$STACK_CLUSTER_NAME" --region "$AWS_REGION" --kubeconfig "$CLUSTER_KUBECONFIG" >/dev/null 2>"$CLUSTER_ERR"; then
  echo "- EKS cluster '$STACK_CLUSTER_NAME' found, kubeconfig obtained"
elif grep -q ResourceNotFoundException "$CLUSTER_ERR"; then
  echo "- No EKS cluster '$STACK_CLUSTER_NAME' in $AWS_REGION"
  echo "Cluster is down."
  cluster_exit 0
else
  echo "- Could not look up EKS cluster '$STACK_CLUSTER_NAME':"
  cat "$CLUSTER_ERR"
  cluster_exit 2
fi

# The API server's own readiness, which needs no RBAC rights, so 'up' does not depend on who is asking.
if cluster_kubectl get --raw /readyz >/dev/null 2>"$CLUSTER_ERR"; then
  # Listing nodes needs more rights than /readyz, and is only there for information.
  if CLUSTER_NODES=$(cluster_kubectl get nodes -o name 2>/dev/null); then
    CLUSTER_NODES_INFO="$(echo "$CLUSTER_NODES" | grep -c .) node(s)"
  else
    CLUSTER_NODES_INFO="node count unavailable"
  fi
  echo "- Up: kubectl reaches the cluster, whose API server is ready ($CLUSTER_NODES_INFO)"
  echo "Cluster is up."
  cluster_exit 0
fi

# The cluster exists but its API server does not answer: being created or deleted, or not reachable
# from here.  Neither up nor down, so say what is known.
CLUSTER_STATUS=$(aws eks describe-cluster --name "$STACK_CLUSTER_NAME" --region "$AWS_REGION" --query cluster.status --output text 2>/dev/null || true)
echo "- EKS cluster '$STACK_CLUSTER_NAME' exists (status ${CLUSTER_STATUS:-unknown}) but kubectl cannot reach it:"
cat "$CLUSTER_ERR"
echo "Could not verify the cluster state."
cluster_exit 2
