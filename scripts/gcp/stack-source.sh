# Sourced by the GCP scripts acting on a stack, given as $1 by its id, eg gcp-polaris, or its role in the cloud (identity,
# catalog or tre), which is looked up in stacks.yaml, see scripts/stack/needs-source.sh: by the launcher when run by
# afon-cli.sh, which passes the id, else by asking the ops command stack-needs, as when run by afon.sh.
# Sets STACK_ID, STACK_TYPE, ie the id without the cloud, and STACK_CLUSTER_NAME, ie the cluster name <stack>-<CLUSTER_NAME>
# as the GcpCloudProvider names it.
STACK_ID="${1:-}"
# <cloud>-<role> is looked up too, as it names the stack with the role, unless a stack is defined under that id, which only the
# lookup knows; any other <cloud>-<type> is the id of a stack, which needs no lookup
case "$(echo "$STACK_ID" | tr '[:upper:]' '[:lower:]')" in
  "") echo "Requires the stack, by its id or its role: <command> <stack-id|identity|catalog|tre>"
      exit 1 ;;
  gcp-identity|gcp-catalog|gcp-tre) NEEDS_STACK_LOOKUP=true ;;
  gcp-*) NEEDS_STACK_LOOKUP= ;;
  *) NEEDS_STACK_LOOKUP=true ;;
esac
if [ -n "$NEEDS_STACK_LOOKUP" ]; then
  source $AFON_ROOT/scripts/stack/needs-source.sh
  stack_needs gcp "$STACK_ID" || exit 1
  STACK_ID="$AFON_STACK_ID"
fi
STACK_TYPE="${STACK_ID#gcp-}"
if [ -z "${CLUSTER_NAME:-}" ]; then
  echo "CLUSTER_NAME is not set, cannot name the cluster of stack $STACK_ID"
  exit 1
fi
STACK_CLUSTER_NAME="$STACK_TYPE-$CLUSTER_NAME"
