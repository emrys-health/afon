# Shared by the <cloud>/polaris.sh status scripts, which source it after afon-source.sh, so that the
# three clouds report Polaris in exactly the same way.  Not run on its own.
#
# A status script checks Polaris layer by layer, each layer being one line of output:
#   1. tools, and the domain to test          -  missing: could not verify (exit 2)
#   2. cloud login                            -  expired or missing: could not verify (exit 2)
#   3. cloud network                          -  none for the cluster: FAILED
#   4. cluster credentials                    -  no such cluster: FAILED
#   5. kubectl can reach the cluster, the Polaris pod is Running and Ready, the ingress has an
#      address, the TLS secret is there (where TLS ends in the cluster)
#   6. a real HTTPS request through DNS -> load balancer -> ingress -> pod
# The kubectl layers say where a failure is (eg no nodes, so a Pending pod); the request is what
# proves Polaris really serves.  An unauthenticated catalog API call is enough for the request, as
# Polaris itself answers 401.  A layer that needs a cluster is skipped when there is none to ask.
# Uses a throwaway kubeconfig, so your current kubectl context is left alone.
#
# The scripts report the status, so 'serving' and 'not serving' are both a successful run: the exit
# code is 0 whenever the status was established and 2 when it could not be verified, so that an
# expired login or a missing tool is never mistaken for Polaris being down.

POLARIS_OK=true
POLARIS_CLUSTER_UP=false
POLARIS_DIR=$(mktemp -d)
POLARIS_KUBECONFIG="$POLARIS_DIR/kubeconfig" # not created, as some tools refuse an empty kubeconfig
POLARIS_ERR="$POLARIS_DIR/stderr"
: > "$POLARIS_ERR"

polaris_kubectl() { kubectl --kubeconfig "$POLARIS_KUBECONFIG" --request-timeout=20s "$@"; }

polaris_exit() { rm -rf "$POLARIS_DIR"; exit "$1"; }

polaris_failed() {
  echo "- $* (FAILED)"
  POLARIS_OK=false
}

# Ends the script with exit 2.  The message is followed by what the last failed command wrote to
# $POLARIS_ERR, if anything.
polaris_cannot_verify() {
  echo "$*"
  if [ -s "$POLARIS_ERR" ]; then
    cat "$POLARIS_ERR"
  fi
  echo "Could not verify Polaris."
  polaris_exit 2
}

# polaris_require_domain <property name> <value>: the domain the HTTPS request goes to.
polaris_require_domain() {
  POLARIS_DOMAIN="$2"
  if [ -z "$POLARIS_DOMAIN" ]; then
    polaris_cannot_verify "$1 is not set, cannot verify Polaris"
  fi
}

# polaris_require <tool>...
polaris_require() {
  local tool
  for tool in "$@"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      polaris_cannot_verify "$tool not found, cannot verify Polaris"
    fi
  done
}

# polaris_require_login <cloud> <login command> <command that fails unless the login is valid>...
polaris_require_login() {
  local cloud="$1" login="$2"
  shift 2
  if "$@" >/dev/null 2>&1; then
    echo "- $cloud login is valid"
  else
    polaris_cannot_verify "- $cloud login expired or missing - log in with: afon-cli.sh $login"
  fi
}

# polaris_check_network <what> <what the listing found, empty for nothing>
polaris_check_network() {
  if [ -n "$2" ]; then
    echo "- $1 found: $2"
  else
    polaris_failed "No $1 found for cluster '$CLUSTER_NAME'"
  fi
}

# polaris_get_credentials <cloud> <regex for the tool's error saying there is no such cluster>
#                         <command that writes the credentials to $POLARIS_KUBECONFIG>...
polaris_get_credentials() {
  local cloud="$1" not_found="$2"
  shift 2
  if "$@" >/dev/null 2>"$POLARIS_ERR"; then
    echo "- $cloud credentials obtained for $CLUSTER_NAME"
    POLARIS_CLUSTER_UP=true
  elif grep -qiE "$not_found" "$POLARIS_ERR"; then
    polaris_failed "No $cloud cluster '$CLUSTER_NAME' found"
  else
    polaris_cannot_verify "- Could not get $cloud credentials for '$CLUSTER_NAME':"
  fi
}

# Whether there is a cluster to ask; if not, says the layer is skipped.
polaris_need_cluster() {
  if [ "$POLARIS_CLUSTER_UP" != "true" ]; then
    echo "- Skipping the $1 check: no cluster to ask"
    return 1
  fi
}

polaris_check_nodes() {
  polaris_need_cluster "kubectl" || return 0
  local nodes
  if nodes=$(polaris_kubectl get nodes -o name 2>/dev/null); then
    echo "- kubectl can reach the cluster ($(echo "$nodes" | grep -c .) node(s))"
  else
    polaris_failed "kubectl cannot reach the cluster"
    POLARIS_CLUSTER_UP=false
  fi
}

polaris_check_pod() {
  polaris_need_cluster "Polaris pod" || return 0
  # '|| true' as kubectl exits non-zero when there is no such pod - the very case to report.
  local status ready
  status=$(polaris_kubectl get pods -l app=polaris -o jsonpath='{.items[0].status.phase}' 2>/dev/null || true)
  ready=$(polaris_kubectl get pods -l app=polaris -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || true)
  if [ "$status" = "Running" ] && [ "$ready" = "true" ]; then
    echo "- Polaris pod is Running and Ready"
  else
    polaris_failed "Polaris pod status='$status' ready='$ready'"
  fi
}

# polaris_check_ingress <ip|hostname> <what to call it>: an ingress gets an IP or a hostname
# depending on the cloud's load balancer.
polaris_check_ingress() {
  polaris_need_cluster "ingress" || return 0
  local address
  address=$(polaris_kubectl get ingress polaris-ingress -o jsonpath="{.status.loadBalancer.ingress[0].$1}" 2>/dev/null || true)
  if [ -n "$address" ]; then
    echo "- Ingress has $2 $address"
  else
    polaris_failed "Ingress has no $2"
  fi
}

polaris_check_tls() {
  polaris_need_cluster "TLS secret" || return 0
  local crt
  crt=$(polaris_kubectl get secret polaris-tls -o jsonpath='{.data.tls\.crt}' 2>/dev/null || true)
  if [ -n "$crt" ]; then
    echo "- TLS secret polaris-tls present"
  else
    polaris_failed "TLS secret polaris-tls missing or empty"
  fi
}

polaris_check_https() {
  local code
  code=$(curl -s -o /dev/null -m 15 -w "%{http_code}" "https://$POLARIS_DOMAIN/api/catalog/v1/config") || true # prints 000 when it can't connect
  case "$code" in
    200|401)
      echo "- Polaris answered HTTPS with HTTP $code"
      ;;
    000)
      polaris_failed "No HTTPS response from $POLARIS_DOMAIN (DNS or connection failed)"
      ;;
    *)
      polaris_failed "HTTPS gave HTTP $code - reachable, but Polaris is not answering"
      ;;
  esac
}

# Prints the verdict and ends the script: exit 0, as the status was established either way.
polaris_report() {
  echo ""
  if [ "$POLARIS_OK" = "true" ]; then
    echo "Polaris is deployed and serving."
  else
    echo "Polaris is NOT deployed correctly - see FAILED lines above."
  fi
  polaris_exit 0
}
