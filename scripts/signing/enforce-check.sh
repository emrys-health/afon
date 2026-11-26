#!/usr/bin/env bash
# Checks that a namespace that is enforced rejects the pods whose images are not signed with the key of the cloud, and admits those whose images
# are, on the cluster of the full stack of the cloud: it tries to create a pod of an image that no one signed, which must be rejected by the
# policy emrys-verify-images-enforce, then one of an image that is mirrored and signed, which must be admitted, see docs/stacks.md.
#
# Usage:
#   ./enforce-check.sh <aws|azure|gcp> <namespace> [<signed image>]
#
#   <cloud>         The cloud of the cluster: that of its full stack, whose kubeconfig is written by '<cloud>-update-kube-config tre', unless
#                   KUBECONFIG is set already, which is then used as it is.
#   <namespace>     The namespace to try the pods in.  If it does not exist it is created, labelled afon.emrys.health/verify-images=enforce, and
#                   deleted at the end, so a throwaway one can be named.  If it exists it must be enforced already: its label is never changed.
#   <signed image>  An image, with its digest, that is mirrored and signed.  Defaults to one that passes in the policy reports, of the registry
#                   of the cloud.
#
# Env vars:
#   UNSIGNED_IMAGE=...  The image that is not signed (default: docker.io/library/nginx:1.27, which no one signs with the key of the cloud).
#   CONTEXT=...         kubectl context to use (defaults to current context).
#
# The pods are deleted at the end; whether the signed one runs does not matter, only that it is admitted.
#
# Exit code: 0 if the unsigned pod is rejected and the signed one admitted, 1 if not.
#
# Examples:
#   ./enforce-check.sh aws sigtest
#   ./enforce-check.sh azure default registry.example/afon/postgres:16-alpine@sha256:...

# No -e, even when run by afon-cli-scripts.sh, which has it: a pod that is rejected, which is what is checked, fails its command, and the result of
# each command is looked at
set -uo pipefail
set +e

usage() {
  echo "Usage: ${BASH_SOURCE[0]} <aws|azure|gcp> <namespace> [<signed image>]" >&2
}

case "${1:-}" in
  -h|--help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
  aws|azure|gcp) CLOUD="$1" ;;
  *) usage; exit 1 ;;
esac
NAMESPACE="${2:-}"
if [ -z "$NAMESPACE" ]; then
  usage
  exit 1
fi
SIGNED_IMAGE="${3:-}"
UNSIGNED_IMAGE="${UNSIGNED_IMAGE:-docker.io/library/nginx:1.27}"
LABEL="afon.emrys.health/verify-images"
POLICY="emrys-verify-images-enforce"

if ! command -v kubectl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo "kubectl and jq are needed on the PATH: run afon-cli.sh local-setup" >&2
  exit 1
fi

# The kubeconfig of the full stack of the cloud, if KUBECONFIG is not set already, as signing-report does
afon() {
  local command="$1"
  shift
  if [ -x "$AFON_ROOT/afon.sh" ]; then
    "$AFON_ROOT/afon.sh" -a "$command" -- "$@"
  else
    SPRING_MAIN_BANNER_MODE="${SPRING_MAIN_BANNER_MODE:-off}" "${AFON_CLI_DIR:-$AFON_ROOT}/afon-cli.sh" "$command" "$@"
  fi
}
if [ -z "${KUBECONFIG:-}" ]; then
  KUBE_CONFIG_OUTPUT=$(afon "$CLOUD-update-kube-config" tre 2>&1) || {
    printf '%s\n' "$KUBE_CONFIG_OUTPUT" | tail -20 >&2
    echo "Could not get the kubeconfig of the $CLOUD stack: is it deployed?  Or set KUBECONFIG to its kubeconfig" >&2
    exit 1
  }
  KUBECONFIG=$(printf '%s\n' "$KUBE_CONFIG_OUTPUT" | sed -n 's/^kubeconfig of .*: export KUBECONFIG=//p' | tail -1)
  if [ -z "$KUBECONFIG" ] || [ ! -s "$KUBECONFIG" ]; then
    echo "No kubeconfig for the $CLOUD stack: set KUBECONFIG to its kubeconfig" >&2
    exit 1
  fi
  export KUBECONFIG
  echo "Using the kubeconfig $KUBECONFIG" >&2
fi
CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
k() { kubectl --context "$CONTEXT" "$@"; }

echo "Cloud: $CLOUD, context: $CONTEXT, namespace: $NAMESPACE"

if ! k get imagevalidatingpolicies.policies.kyverno.io "$POLICY" >/dev/null 2>&1; then
  echo "FAIL  the policy $POLICY does not exist: is Kyverno installed, and the public key set (see docs/stacks.md)?" >&2
  exit 1
fi

# A namespace that does not exist is created enforced, and deleted at the end; one that exists must be enforced already
CREATED=false
if k get namespace "$NAMESPACE" >/dev/null 2>&1; then
  MODE="$(k get namespace "$NAMESPACE" -o json | jq -r --arg l "$LABEL" '.metadata.labels[$l] // "none"')"
  if [ "$MODE" != "enforce" ]; then
    echo "FAIL  the namespace $NAMESPACE is labelled $LABEL=$MODE, not enforce: this check does not change the label of a namespace that exists." >&2
    echo "      Name a namespace that does not exist, to have a throwaway one, or enforce it: kubectl label namespace $NAMESPACE $LABEL=enforce --overwrite" >&2
    exit 1
  fi
else
  k create namespace "$NAMESPACE" >/dev/null && k label namespace "$NAMESPACE" "$LABEL=enforce" >/dev/null || exit 1
  CREATED=true
  echo "Created the namespace $NAMESPACE, labelled $LABEL=enforce"
fi

cleanup() {
  k --namespace "$NAMESPACE" delete pod enforce-check-unsigned enforce-check-signed --ignore-not-found --wait=false >/dev/null 2>&1
  if [ "$CREATED" = true ]; then
    k delete namespace "$NAMESPACE" --wait=false >/dev/null 2>&1
    echo "Deleting the namespace $NAMESPACE"
  fi
}
trap cleanup EXIT

# An image that passes, of the registry of the cloud (the host of the registry from the config, else the usual hosts of the clouds), with its
# digest, which is how the signature is found
if [ -z "$SIGNED_IMAGE" ]; then
  REGISTRY_VAR="$(printf '%s' "$CLOUD" | tr '[:lower:]' '[:upper:]')_IMAGE_REGISTRY"
  REGISTRY="${!REGISTRY_VAR:-}"
  REGISTRY="${REGISTRY%%/*}"
  case "$CLOUD" in
    aws) REGISTRY_PATTERN='[0-9]+\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com' ;;
    azure) REGISTRY_PATTERN='[a-z0-9]+\.azurecr\.io' ;;
    *) REGISTRY_PATTERN='([a-z0-9-]+-docker\.pkg\.dev|([a-z]+\.)?gcr\.io)' ;;
  esac
  WORK_DIR="$(mktemp -d)"
  trap 'rm -rf "$WORK_DIR"; cleanup' EXIT
  k get policyreport --all-namespaces -o json > "$WORK_DIR/reports.json" 2>/dev/null
  k get pods --all-namespaces -o json > "$WORK_DIR/pods.json" 2>/dev/null
  SIGNED_IMAGE="$(jq -n -r --arg registry "$REGISTRY" --arg pattern "$REGISTRY_PATTERN" \
    --slurpfile reports "$WORK_DIR/reports.json" --slurpfile pods "$WORK_DIR/pods.json" '
    def ours: contains("@sha256:") and (if $registry != "" then startswith($registry + "/") else test("^(" + $pattern + ")/") end);
    ([$reports[0].items[] | select(.scope.kind == "Pod" and .summary.pass > 0 and .summary.fail == 0 and .summary.error == 0) | .scope.uid]) as $passed
    | [$pods[0].items[] | select(.metadata.uid as $u | $passed | index($u)) | .spec.containers[]?.image | select(ours)] | first // empty')"
  if [ -z "$SIGNED_IMAGE" ]; then
    echo "FAIL  no signed image found in the policy reports: give one, with its digest, as the third argument" >&2
    exit 1
  fi
fi

STATUS=0

echo
echo "1. Unsigned image, which must be rejected: $UNSIGNED_IMAGE"
OUTPUT="$(k --namespace "$NAMESPACE" run enforce-check-unsigned --image="$UNSIGNED_IMAGE" --restart=Never 2>&1)"
RC=$?
if [ "$RC" -ne 0 ] && grep -qi "denied\|rejected\|policy\|admission webhook" <<<"$OUTPUT"; then
  echo "PASS  rejected: $(printf '%s' "$OUTPUT" | tr '\n' ' ' | cut -c1-300)"
elif [ "$RC" -ne 0 ]; then
  echo "FAIL  not created, but not rejected by the policy either: $OUTPUT"
  STATUS=1
else
  echo "FAIL  admitted: the namespace does not enforce the signatures"
  STATUS=1
fi

echo
echo "2. Signed image, which must be admitted: $SIGNED_IMAGE"
OUTPUT="$(k --namespace "$NAMESPACE" run enforce-check-signed --image="$SIGNED_IMAGE" --restart=Never 2>&1)"
if [ $? -eq 0 ]; then
  echo "PASS  admitted: $OUTPUT"
else
  echo "FAIL  rejected: $(printf '%s' "$OUTPUT" | tr '\n' ' ' | cut -c1-300)"
  STATUS=1
fi

echo
if [ "$STATUS" -eq 0 ]; then
  echo "The namespace $NAMESPACE rejects the unsigned image and admits the signed one."
else
  echo "The namespace $NAMESPACE does not behave as an enforced one should."
fi
exit "$STATUS"
