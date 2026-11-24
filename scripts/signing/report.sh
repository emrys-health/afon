#!/usr/bin/env bash
# Reports, from the policy reports that Kyverno writes, which pods have images that are not signed with the key of the cloud, on the
# current kubectl context/cluster: the pods the Kyverno image policies checked (those of the namespaces labelled
# afon.emrys.health/verify-images, see docs/stacks.md), whether each passed, and the images of the pods that failed.
#
# Usage:
#   ./report.sh <aws|azure|gcp> [<namespace>|all] [--images|--json]
#
#   <cloud>      The cloud of the cluster to report on: that of its full stack (the stack with the role tre), as the smoke tests, whose
#                kubeconfig is written with '<cloud>-update-kube-config tre', unless KUBECONFIG is set already, which is then used as it is.
#   <namespace>  The namespace to report on.  Defaults to all.
#   --images     Only the images of the pods that failed, with the number of pods, ie what is left to sign.
#   --json       The report as JSON: its summary and each pod checked, with its result, policies and images.
#
# Env vars:
#   CONTEXT=...  kubectl context to use (defaults to current context).
#
# A pod that failed may have images that are signed too: the report is of the pod, not of each image.  Kyverno also reports on the
# controllers that make the pods (Deployments, ReplicaSets...), which are left out as they repeat what their pods say.
#
# Examples:
#   ./report.sh azure
#   ./report.sh azure default --images
#   ./report.sh azure all --json | jq '.summary'

set -euo pipefail

usage() {
  echo "Usage: ${BASH_SOURCE[0]} <aws|azure|gcp> [<namespace>|all] [--images|--json]" >&2
}

NAMESPACE="all"
FORMAT="table"
CLOUD=""
for ARG in "$@"; do
  case "$ARG" in
    --images) FORMAT="images" ;;
    --json) FORMAT="json" ;;
    -h|--help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --*) echo "Unknown option $ARG" >&2; usage; exit 1 ;;
    aws|azure|gcp) if [ -z "$CLOUD" ]; then CLOUD="$ARG"; else NAMESPACE="$ARG"; fi ;;
    *) if [ -z "$CLOUD" ]; then echo "Expected the cloud first, not '$ARG'" >&2; usage; exit 1; fi; NAMESPACE="$ARG" ;;
  esac
done
if [ -z "$CLOUD" ]; then
  echo "A cloud is required" >&2
  usage
  exit 1
fi

for TOOL in kubectl jq; do
  if ! command -v "$TOOL" >/dev/null 2>&1; then
    echo "$TOOL not found on PATH: run afon-cli.sh local-setup" >&2
    exit 1
  fi
done

# The kubeconfig of the full stack of the cloud, if KUBECONFIG is not set already: the default ~/.kube/config is neither written nor read
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
if [[ "$NAMESPACE" == "all" ]]; then
  NS_ARGS=(--all-namespaces)
else
  NS_ARGS=(--namespace "$NAMESPACE")
fi

if ! kubectl --context "$CONTEXT" api-resources --api-group=wgpolicyk8s.io -o name 2>/dev/null | grep -q '^policyreports'; then
  echo "No policy reports on $CONTEXT: Kyverno is not installed, or its reports controller is not running" >&2
  exit 1
fi

# In files, as the reports and the pods of a cluster are too much to pass to jq as arguments
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
kubectl --context "$CONTEXT" get policyreport "${NS_ARGS[@]}" -o json > "$WORK_DIR/reports.json"
kubectl --context "$CONTEXT" get pods "${NS_ARGS[@]}" -o json > "$WORK_DIR/pods.json"

# Each pod that a report of the image policies is about, with the images of the pod, which the report does not give
REPORT="$(jq -n --slurpfile reports "$WORK_DIR/reports.json" --slurpfile pods "$WORK_DIR/pods.json" '
  def result: if .summary.fail > 0 then "fail" elif .summary.error > 0 then "error" elif .summary.pass > 0 then "pass" else "skip" end;
  ($pods[0].items | map({key: .metadata.uid, value: ([.spec.containers[]?.image] + [.spec.initContainers[]?.image] | unique)}) | from_entries) as $images
  | [$reports[0].items[]
     | select(.scope.kind == "Pod")
     | select(any(.results[]?; .source == "KyvernoImageValidatingPolicy"))
     | {namespace: .scope.namespace, pod: .scope.name, result: result,
        policies: ([.results[]? | select(.source == "KyvernoImageValidatingPolicy") | .policy] | unique),
        images: ($images[.scope.uid] // [])}]
  | sort_by(.result, .namespace, .pod) as $checked
  | {summary: ($checked | group_by(.result) | map({key: .[0].result, value: length}) | from_entries
               | {checked: ($checked | length), pass: (.pass // 0), fail: (.fail // 0), error: (.error // 0), skip: (.skip // 0)}),
     pods: $checked}')"

if [[ "$(jq '.summary.checked' <<<"$REPORT")" == "0" ]]; then
  echo "No pod has been checked by the image policies (namespace: $NAMESPACE, context: $CONTEXT)." >&2
  echo "Check that the policies exist and are ready: kubectl get imagevalidatingpolicies.policies.kyverno.io" >&2
  echo "and that the namespace is labelled: kubectl get ns --show-labels | grep verify-images" >&2
  exit 1
fi

# The images of the pods that failed, with the number of pods
failing_images() {
  jq -r '[.pods[] | select(.result != "pass") | .images[]] | group_by(.) | map({image: .[0], pods: length}) | sort_by(-.pods, .image)
         | .[] | "\(.pods)\t\(.image)"' <<<"$REPORT"
}

case "$FORMAT" in
  json)
    echo "$REPORT"
    ;;
  images)
    failing_images | awk -F'\t' '{ printf "%5d pod(s)  %s\n", $1, $2 }'
    ;;
  table)
    echo "Cloud: $CLOUD, context: $CONTEXT"
    jq -r '.summary | "Checked \(.checked) pods: \(.pass) pass, \(.fail) fail, \(.error) error, \(.skip) skip"' <<<"$REPORT"
    echo
    jq -r '(["NAMESPACE","POD","RESULT","POLICY","IMAGES"] | @tsv),
           (.pods[] | [.namespace, .pod, .result, (.policies | join(",")), (.images | join(","))] | @tsv)' <<<"$REPORT" | column -t -s $'\t'
    echo
    echo "Images of the pods that failed, with the number of pods:"
    failing_images | awk -F'\t' '{ printf "%5d  %s\n", $1, $2 }'
    ;;
esac
