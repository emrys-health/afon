#!/usr/bin/env bash
# Estimates, before deploying, whether the pods of a stack fit on the nodes of a cluster, by CPU and memory REQUESTS, which
# is what the scheduler places pods by (not what they use).  A pod that no node has the requests left for stays Pending, eg
# "0/3 nodes are available: 3 Insufficient cpu", until a Helm release with --atomic gives up.
#
# It adds up, from the synthesised plan of the stack (cdk.tf.json in its dir under $CDKTF_DIR/stacks):
#   - the Deployments and StatefulSets of the plan, with the requests of their containers;
#   - the Helm releases, rendered with `helm template` of the vendored chart and the values of the plan;
#   - the Spark pods that the plan only creates through custom resources: the SparkCluster master and workers, whose requests
#     the operator leaves unset, and the driver of the SparkApplication, which Spark sizes by its own defaults.
# It then places them on the nodes, the biggest first, after what AKS itself runs on each node, with those that arrive last,
# eg the Spark driver, placed once the others are.  The scheduler spreads pods over the emptiest nodes, as it does here.
#
# The estimate is only as good as what it can see, so it lists the pods that request nothing: the scheduler counts them as
# free, but they use CPU and memory at run time, so a cluster that "fits" is still tighter than it looks.
#
# Usage:
#   ./estimator.sh <stack-id|stack-dir> [<nodes>] [<vm-size>]
#
#   stack-id    The stack, eg azure-full, whose plan is in $CDKTF_DIR/stacks/<stack-id>, synthesised by validate-stack or
#               deploy.  Or <cloud>-<role>, eg azure-tre, if run by afon-cli.sh or afon.sh.  Or its dir.
#   nodes       The number of nodes of the cluster.  Defaults to 3.
#   vm-size     The size of the nodes, one of the ones known, see ALLOCATABLE below.  Defaults to Standard_D2s_v3.
#
# Env vars:
#   ALLOC_CPU=...  The CPU a node has to give to pods, in m, instead of that of the vm size.  With ALLOC_MEM.
#   ALLOC_MEM=...  The memory a node has to give to pods, in Mi, instead of that of the vm size.  With ALLOC_CPU.
#   USERS=...      The number of notebook users, instead of the number the plan has.
#
# Needs jq and helm, and something to convert the YAML that helm renders, the first of these found: yq (mikefarah's, v4),
# ruby, or python3 with PyYAML.  Exits 1 if any pod would stay Pending.
#
# Examples:
#   ./estimator.sh azure-full
#   ./estimator.sh azure-full 4 Standard_D2s_v3
#   ALLOC_CPU=3860 ALLOC_MEM=13300 ./estimator.sh azure-full 2 Standard_D4s_v3

set -euo pipefail

STACK="${1:-}"
NODES="${2:-3}"
VM_SIZE="${3:-Standard_D2s_v3}"

if [ -z "$STACK" ]; then
  echo "Usage: ${BASH_SOURCE[0]} <stack-id|stack-dir> [<nodes>] [<vm-size>]" >&2
  echo "  e.g. ${BASH_SOURCE[0]} azure-full 4 Standard_D2s_v3" >&2
  exit 1
fi

for TOOL in jq helm awk; do
  if ! command -v "$TOOL" >/dev/null 2>&1; then
    echo "$TOOL not found on PATH" >&2
    exit 1
  fi
done

# What converts the YAML that helm renders to JSON, as one document per line: the first of these found, as none is on every system
if command -v yq >/dev/null 2>&1 && yq --version 2>&1 | grep -q mikefarah; then
  YAML_CONVERTER=yq
elif command -v ruby >/dev/null 2>&1; then
  YAML_CONVERTER=ruby
elif command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
  YAML_CONVERTER=python3
else
  echo "Nothing found to convert the YAML rendered by helm: install one of yq (mikefarah's, v4), ruby, or python3 with PyYAML" >&2
  exit 1
fi

# yaml_to_json: reads YAML documents on stdin, prints each as a line of JSON
yaml_to_json() {
  case "$YAML_CONVERTER" in
    yq)      yq eval-all -o=json -I=0 '.' - ;;
    ruby)    ruby -ryaml -rjson -e 'YAML.load_stream(STDIN.read).compact.each { |doc| puts doc.to_json }' ;;
    python3) python3 -c 'import sys, json, yaml; [print(json.dumps(doc)) for doc in yaml.safe_load_all(sys.stdin) if doc]' ;;
  esac
}

STACKS_DIR="${CDKTF_DIR:-${AFON_CDKTF:-$HOME/.afon/cdktf}}/stacks"
# The stack may also be given as <cloud>-<role>, eg azure-tre, the stack of the cloud with the role in stacks.yaml, which is
# looked up by the launcher, or by asking the ops command stack-needs, see scripts/stack/needs-source.sh: only if there is
# one to ask, ie the script is run by afon-cli.sh or afon.sh, and the arg is not a dir or the id of a stack with a plan
if [ ! -d "$STACK" ] && [ ! -d "$STACKS_DIR/$STACK" ] && [[ "$STACK" =~ ^[A-Za-z]+-[A-Za-z0-9]+$ ]] \
   && [ -f "${AFON_ROOT:-/nonexistent}/scripts/stack/needs-source.sh" ]; then
  STACK_LOWER=$(echo "$STACK" | tr '[:upper:]' '[:lower:]')
  case "$STACK_LOWER" in
    aws-*|azure-*|gcp-*)
      source "$AFON_ROOT/scripts/stack/needs-source.sh"
      unset AFON_STACK_ID
      if stack_needs "${STACK_LOWER%%-*}" "$STACK_LOWER" >/dev/null 2>&1 && [ -n "${AFON_STACK_ID:-}" ]; then
        STACK="$AFON_STACK_ID"
      fi ;;
  esac
fi
if [ -d "$STACK" ]; then
  STACK_DIR="$STACK"
else
  STACK_DIR="$STACKS_DIR/$STACK"
fi
if [ ! -f "$STACK_DIR/cdk.tf.json" ]; then
  echo "No plan at $STACK_DIR/cdk.tf.json: synthesise the stack first, eg: afon-cli.sh validate-stack $STACK" >&2
  exit 1
fi
PLAN="$STACK_DIR/cdk.tf.json"

# What a node of the size has to give to pods (m of CPU, Mi of memory).  Standard_D2s_v3 is as measured on an AKS 1.36 node
# (1900m, 5929 Mi); the others are approximations from AKS's reservation formula, so give ALLOC_CPU and ALLOC_MEM when it matters.
case "$VM_SIZE" in
  Standard_D2s_v3|Standard_D2s_v5) DEFAULT_ALLOC_CPU=1900; DEFAULT_ALLOC_MEM=5789 ;;
  Standard_D4s_v3|Standard_D4s_v5) DEFAULT_ALLOC_CPU=3860; DEFAULT_ALLOC_MEM=13300 ;;
  Standard_D8s_v3|Standard_D8s_v5) DEFAULT_ALLOC_CPU=7820; DEFAULT_ALLOC_MEM=28400 ;;
  *) DEFAULT_ALLOC_CPU=; DEFAULT_ALLOC_MEM= ;;
esac
ALLOC_CPU="${ALLOC_CPU:-$DEFAULT_ALLOC_CPU}"
ALLOC_MEM="${ALLOC_MEM:-$DEFAULT_ALLOC_MEM}"
if [ -z "$ALLOC_CPU" ] || [ -z "$ALLOC_MEM" ]; then
  echo "Unknown vm size $VM_SIZE: give ALLOC_CPU and ALLOC_MEM, or one of Standard_D{2,4,8}s_v{3,5}" >&2
  exit 1
fi

# What AKS runs in kube-system, as measured on that cluster: pods spread over the nodes, and the DaemonSets, on each node.
SYSTEM_FIXED_CPU=594
SYSTEM_FIXED_MEM=484
SYSTEM_NODE_CPU=210
SYSTEM_NODE_MEM=476
# Spark's own defaults for the driver (spark.driver.cores = 1; spark.driver.memory = 1g plus the overhead of 10%, at least 384m)
SPARK_DRIVER_CPU=1000
SPARK_DRIVER_MEM=1408

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PODS="$WORK/pods.tsv"
: > "$PODS"

# jq definitions: Kubernetes quantities to m of CPU and Mi of memory, and the requests of a pod, ie the sum of those of its
# containers, or the largest of its init containers if more.  A pod is a row: name, source, count, per node, cpu, mem, late, note
JQ_DEFS='
def cpum: tostring | if endswith("m") then (.[:-1] | tonumber) else (tonumber * 1000) end;
def memmi: tostring
  | if endswith("Ki") then (.[:-2] | tonumber / 1024)
    elif endswith("Mi") then (.[:-2] | tonumber)
    elif endswith("Gi") then (.[:-2] | tonumber * 1024)
    elif endswith("Ti") then (.[:-2] | tonumber * 1048576)
    elif endswith("k") then (.[:-1] | tonumber * 1000 / 1048576)
    elif endswith("M") then (.[:-1] | tonumber * 1000000 / 1048576)
    elif endswith("G") then (.[:-1] | tonumber * 1000000000 / 1048576)
    else (tonumber / 1048576) end;
def requests: (.resources // {}) | (if type == "array" then (.[0] // {}) else . end) | (.requests // {});
def podcpu($cs; $inits): ([$cs[] | (requests.cpu // 0 | cpum)] | add // 0) as $c
  | ([$inits[] | (requests.cpu // 0 | cpum)] | max // 0) as $i | [$c, $i] | max;
def podmem($cs; $inits): ([$cs[] | (requests.memory // 0 | memmi)] | add // 0) as $c
  | ([$inits[] | (requests.memory // 0 | memmi)] | max // 0) as $i | [$c, $i] | max;
def row($name; $source; $count; $pernode; $cpu; $mem; $late; $note):
  [$name, $source, $count, $pernode, ($cpu | round), ($mem | round), $late, $note] | @tsv;
'

# The Deployments and StatefulSets of the plan
jq -r --argjson nodes "$NODES" "$JQ_DEFS"'
  .resource as $r
  | [["kubernetes_deployment", "Deployment"], ["kubernetes_stateful_set", "StatefulSet"], ["kubernetes_daemon_set", "DaemonSet"]][]
  | . as [$kind, $label]
  | ($r[$kind] // {}) | to_entries[]
  | .key as $key | .value as $v | $v.spec.template.spec as $pod
  | (($v.metadata // {}) | (if type == "array" then .[0] else . end) | .name // $key) as $name
  | ($label == "DaemonSet") as $daemon
  | row($name; "plan " + $label; (if $daemon then $nodes else ($v.spec.replicas // 1 | tonumber) end); (if $daemon then 1 else 0 end);
        podcpu($pod.container // []; $pod.init_container // []); podmem($pod.container // []; $pod.init_container // []); 0; "")
' "$PLAN" >> "$PODS"

# The Helm releases, rendered with the values of the plan
while IFS= read -r RELEASE; do
  [ -n "$RELEASE" ] || continue
  RELEASE_NAME="$(jq -r '.name' <<< "$RELEASE")"
  CHART="$STACK_DIR/$(jq -r '.chart' <<< "$RELEASE")"
  HELM_ARGS=(template "$RELEASE_NAME" "$CHART" --namespace "$(jq -r '.namespace // "default"' <<< "$RELEASE")" --no-hooks)
  VALUES_COUNT="$(jq -r '(.values // []) | length' <<< "$RELEASE")"
  for ((I = 0; I < VALUES_COUNT; I++)); do
    jq -r --argjson i "$I" '.values[$i] | if type == "string" then . else tojson end' <<< "$RELEASE" > "$WORK/$RELEASE_NAME-$I.yaml"
    HELM_ARGS+=(-f "$WORK/$RELEASE_NAME-$I.yaml")
  done
  while IFS= read -r SET_ARG; do
    HELM_ARGS+=(--set "$SET_ARG")
  done < <(jq -r '(.set // [])[] | "\(.name)=\(.value)"' <<< "$RELEASE")
  if ! helm "${HELM_ARGS[@]}" > "$WORK/rendered.yaml" 2> "$WORK/helm.err"; then
    echo "helm template of $RELEASE_NAME failed: $(head -c 400 "$WORK/helm.err")" >&2
    exit 1
  fi
  yaml_to_json < "$WORK/rendered.yaml" \
    | jq -r --argjson nodes "$NODES" --arg release "$RELEASE_NAME" "$JQ_DEFS"'
        select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "DaemonSet")
        | .kind as $kind | .spec.template.spec as $pod | ($kind == "DaemonSet") as $daemon
        | row(.metadata.name; "helm " + $kind + " (" + $release + ")";
              (if $daemon then $nodes else (.spec.replicas // 1 | tonumber) end); (if $daemon then 1 else 0 end);
              podcpu($pod.containers // []; $pod.initContainers // []); podmem($pod.containers // []; $pod.initContainers // []); 0; "")
      ' >> "$PODS"
done < <(jq -c '(.resource.helm_release // {}) | to_entries[] | .value' "$PLAN")

# The Spark pods that the plan creates through custom resources, applied by kubectl from its null resources
while IFS= read -r DOC; do
  [ -n "$DOC" ] || continue
  jq -r --argjson driver_cpu "$SPARK_DRIVER_CPU" --argjson driver_mem "$SPARK_DRIVER_MEM" "$JQ_DEFS"'
    if .kind == "SparkCluster" then
      row(.metadata.name + "-master"; "SparkCluster"; 1; 0; 0; 0; 0; "the operator sets no requests"),
      row(.metadata.name + "-worker"; "SparkCluster"; (.spec.clusterTolerations.instanceConfig.initWorkers // 1 | tonumber); 0; 0; 0; 0;
          "the operator sets no requests")
    elif .kind == "SparkApplication" then
      row(.metadata.name + "-driver"; "SparkApplication"; 1; 0;
          (if .spec.sparkConf["spark.driver.cores"] then (.spec.sparkConf["spark.driver.cores"] | cpum) else $driver_cpu end);
          $driver_mem; 1; "from Spark'"'"'s defaults for the driver, as the plan sets none")
    else empty end
  ' <<< "$DOC" >> "$PODS"
done < <(jq -r '(.resource.null_resource // {}) | to_entries[] | .value.provisioner[]? | ."local-exec".command // empty' "$PLAN" \
         | sed -n "s/^echo '\(.*\)' | kubectl apply.*/\1/p")

# The notebooks JupyterHub spawns, one per user
if [ -n "${USERS:-}" ]; then
  NOTEBOOKS="$USERS"
else
  NOTEBOOKS="$(jq -r '[(.resource.kubernetes_service // {}) | keys[] | select(startswith("guac-vnc-"))] | length' "$PLAN")"
fi
if [ "$NOTEBOOKS" -gt 0 ]; then
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "notebook (one per user, spawned at login)" "JupyterHub" "$NOTEBOOKS" 0 0 0 0 "no requests are set" >> "$PODS"
fi

awk -F '\t' -v stack="$(basename "$STACK_DIR")" -v nodes="$NODES" -v vm="$VM_SIZE" -v alloc_c="$ALLOC_CPU" -v alloc_m="$ALLOC_MEM" \
    -v sys_fixed_c="$SYSTEM_FIXED_CPU" -v sys_fixed_m="$SYSTEM_FIXED_MEM" -v sys_node_c="$SYSTEM_NODE_CPU" -v sys_node_m="$SYSTEM_NODE_MEM" '
  { n++; name[n] = $1; src[n] = $2; cnt[n] = $3 + 0; pernode[n] = $4 + 0; c[n] = $5 + 0; m[n] = $6 + 0; late[n] = $7 + 0; note[n] = $8 }

  # idx[1..k] sorted by the key (desc), where key is cpu * count when bycount, else cpu then memory
  function sort_by(k, bycount,    i, j, t, a, b) {
    for (i = 2; i <= k; i++) {
      t = idx[i]
      for (j = i - 1; j >= 1; j--) {
        a = idx[j]
        if (bycount ? (c[a] * cnt[a] >= c[t] * cnt[t]) : (c[a] > c[t] || (c[a] == c[t] && m[a] >= m[t]))) break
        idx[j + 1] = idx[j]
      }
      idx[j + 1] = t
    }
  }

  END {
    printf "Stack %s on %d x %s (allocatable per node: %dm CPU, %d Mi)\n", stack, nodes, vm, alloc_c, alloc_m

    k = 0
    for (i = 1; i <= n; i++) if (c[i] > 0 || m[i] > 0) idx[++k] = i
    sort_by(k, 1)
    for (r = 1; r <= k; r++) ord[r] = idx[r]
    print "\nPods that request resources (what the scheduler places by):"
    printf "  %-52s %-34s %4s %8s %8s\n", "pod", "source", "x", "cpu(m)", "mem(Mi)"
    tot_c = sys_node_c * nodes + sys_fixed_c; tot_m = sys_node_m * nodes + sys_fixed_m
    for (r = 1; r <= k; r++) {
      i = ord[r]
      printf "  %-52s %-34s %4d %8d %8d%s\n", substr(name[i], 1, 52), substr(src[i], 1, 34), cnt[i], c[i], m[i], (pernode[i] ? "  per node" : "")
      tot_c += c[i] * cnt[i]; tot_m += m[i] * cnt[i]
    }
    printf "  %-52s %-34s %4s %8d %8d\n", "+ AKS system pods (kube-system, measured)", "", "", sys_fixed_c + sys_node_c * nodes, sys_fixed_m + sys_node_m * nodes
    printf "  %-52s %-34s %4s %8d %8d\n", "TOTAL requested", "", "", tot_c, tot_m
    printf "  %-52s %-34s %4s %8d %8d\n", "Allocatable", "", "", alloc_c * nodes, alloc_m * nodes

    # the free CPU and memory of each node: what the system takes, spread, and the pods on each node
    for (q = 1; q <= nodes; q++) { fc[q] = alloc_c - sys_node_c - sys_fixed_c / nodes; fm[q] = alloc_m - sys_node_m - sys_fixed_m / nodes }
    for (r = 1; r <= k; r++) {
      i = ord[r]
      if (pernode[i]) for (q = 1; q <= nodes; q++) { fc[q] -= c[i]; fm[q] -= m[i] }
    }
    # the others, those arriving first then those arriving last, each the biggest first
    unplaced_n = 0
    for (phase = 0; phase <= 1; phase++) {
      kk = 0
      for (r = 1; r <= k; r++) { i = ord[r]; if (!pernode[i] && late[i] == phase) tmp[++kk] = i }
      # sorted by cpu then memory
      for (r = 1; r <= kk; r++) idx[r] = tmp[r]
      sort_by(kk, 0)
      for (r = 1; r <= kk; r++) {
        i = idx[r]
        for (rep = 1; rep <= cnt[i]; rep++) {
          best = 0
          for (q = 1; q <= nodes; q++) if (fc[q] >= c[i] && fm[q] >= m[i] && (best == 0 || fc[q] > fc[best])) best = q
          if (best == 0) { if (!(i in seen)) { seen[i] = 1; unplaced[++unplaced_n] = i } }
          else { fc[best] -= c[i]; fm[best] -= m[i] }
        }
      }
    }
    line = ""
    max_c = 0; max_m = 0
    for (q = 1; q <= nodes; q++) {
      line = line (q > 1 ? ", " : "") sprintf("%dm / %d", fc[q], fm[q])
      if (fc[q] > max_c) max_c = fc[q]
      if (fm[q] > max_m) max_m = fm[q]
    }
    print "\nFree per node after placing them (m CPU, Mi memory): " line
    if (unplaced_n > 0) {
      print "\nWOULD STAY PENDING (no node has the requests left):"
      for (u = 1; u <= unplaced_n; u++) {
        i = unplaced[u]
        printf "  - %s: needs %dm CPU, %d Mi; the emptiest node has %dm, %d Mi\n", name[i], c[i], m[i], max_c, max_m
      }
    } else print "\nAll the pods that request resources fit."

    for (i = 1; i <= n; i++) if (name[i] ~ /^kube-prometheus-stack/) { prom = 1 }
    if (prom) print "\nNot counted: the Prometheus and Alertmanager pods, which the Prometheus operator creates from custom resources (the chart sets no requests for them by default), and the Spark executors, if any."

    header = 0
    for (i = 1; i <= n; i++) if (c[i] == 0 && m[i] == 0) {
      if (!header) { print "\nPods that request NOTHING (counted as free by the scheduler, but they use CPU and memory when running):"; header = 1 }
      printf "  - %s x%d (%s)%s\n", name[i], cnt[i], src[i], (note[i] != "" ? ": " note[i] : "") | "sort"
    }
    close("sort")
    exit (unplaced_n > 0 ? 1 : 0)
  }
' "$PODS"
