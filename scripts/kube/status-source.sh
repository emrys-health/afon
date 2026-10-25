# Shared by the status scripts, eg <cloud>/polaris.sh and aws/keycloak.sh, through the source of what they check, eg
# kube/polaris-source.sh, which sets STATUS_SUBJECT, eg Polaris, before sourcing it.  Not run on its own.
#
# A status script checks its subject layer by layer, each layer being one line of output:
#   1. tools, and the domain to test          -  missing: could not verify (exit 2)
#   2. cloud login                            -  expired or missing: could not verify (exit 2)
#   3. cloud network                          -  none for the cluster: FAILED
#   4. cluster credentials                    -  no such cluster: FAILED
#   5. kubectl can reach the cluster, the subject's pod is Running and Ready, its ingress has an
#      address, its TLS secret is there (where TLS ends in the cluster)
#   6. real HTTPS requests through DNS -> load balancer -> ingress -> pod, first without credentials, then with,
#      as defined by the source of the subject
# The kubectl layers say where a failure is (eg no nodes, so a Pending pod); the requests are what prove the
# subject really serves.  A layer that needs a cluster is skipped when there is none to ask - each skip is a line
# of output, not a failure.
# Uses a throwaway kubeconfig, so your current kubectl context is left alone.  Secrets and tokens are never
# printed, nor put on a command line where ps would show them.
#
# The scripts report the status, so 'serving' and 'not serving' are both a successful run: the exit code is 0
# whenever the status was established and 2 when it could not be verified, so that an expired login or a missing
# tool is never mistaken for the subject being down.

# What the script reports on, eg Polaris, set by the source of the subject
STATUS_SUBJECT="${STATUS_SUBJECT:-the stack}"
# The cluster the subject runs in, set where it isn't $CLUSTER_NAME, eg on AWS <stack>-<CLUSTER_NAME>
STATUS_CLUSTER_NAME="${STATUS_CLUSTER_NAME:-${CLUSTER_NAME:-}}"
STATUS_OK=true
STATUS_CLUSTER_UP=false
STATUS_DIR=$(mktemp -d)
STATUS_KUBECONFIG="$STATUS_DIR/kubeconfig" # not created, as some tools refuse an empty kubeconfig
STATUS_ERR="$STATUS_DIR/stderr"
: > "$STATUS_ERR"

status_kubectl() { kubectl --kubeconfig "$STATUS_KUBECONFIG" --request-timeout=20s "$@"; }

status_exit() { rm -rf "$STATUS_DIR"; exit "$1"; }

status_failed() {
  echo "- $* (FAILED)"
  STATUS_OK=false
}

# Ends the script with exit 2.  The message is followed by what the last failed command wrote to
# $STATUS_ERR, if anything.
status_cannot_verify() {
  echo "$*"
  if [ -s "$STATUS_ERR" ]; then
    cat "$STATUS_ERR"
  fi
  echo "Could not verify $STATUS_SUBJECT."
  status_exit 2
}

# status_require_domain <property name> <value>: the domain the HTTPS requests go to.
status_require_domain() {
  STATUS_DOMAIN="$2"
  if [ -z "$STATUS_DOMAIN" ]; then
    status_cannot_verify "$1 is not set, cannot verify $STATUS_SUBJECT"
  fi
}

# status_require <tool>...
status_require() {
  local tool
  for tool in "$@"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      status_cannot_verify "$tool not found, cannot verify $STATUS_SUBJECT"
    fi
  done
}

# status_require_login <cloud> <login command> <command that fails unless the login is valid>...
status_require_login() {
  local cloud="$1" login="$2"
  shift 2
  if "$@" >/dev/null 2>&1; then
    echo "- $cloud login is valid"
  else
    status_cannot_verify "- $cloud login expired or missing - log in with: afon-cli.sh $login"
  fi
}

# status_check_network <what> <what the listing found, empty for nothing>
status_check_network() {
  if [ -n "$2" ]; then
    echo "- $1 found: $2"
  else
    status_failed "No $1 found for cluster '$STATUS_CLUSTER_NAME'"
  fi
}

# status_get_credentials <cloud> <regex for the tool's error saying there is no such cluster>
#                        <command that writes the credentials to $STATUS_KUBECONFIG>...
status_get_credentials() {
  local cloud="$1" not_found="$2"
  shift 2
  if "$@" >/dev/null 2>"$STATUS_ERR"; then
    echo "- $cloud credentials obtained for $STATUS_CLUSTER_NAME"
    STATUS_CLUSTER_UP=true
  elif grep -qiE "$not_found" "$STATUS_ERR"; then
    status_failed "No $cloud cluster '$STATUS_CLUSTER_NAME' found"
  else
    status_cannot_verify "- Could not get $cloud credentials for '$STATUS_CLUSTER_NAME':"
  fi
}

# Whether there is a cluster to ask; if not, says the layer is skipped.
status_need_cluster() {
  if [ "$STATUS_CLUSTER_UP" != "true" ]; then
    echo "- Skipping the $1 check: no cluster to ask"
    return 1
  fi
}

status_check_nodes() {
  status_need_cluster "kubectl" || return 0
  local nodes
  if nodes=$(status_kubectl get nodes -o name 2>/dev/null); then
    echo "- kubectl can reach the cluster ($(echo "$nodes" | grep -c .) node(s))"
  else
    status_failed "kubectl cannot reach the cluster"
    STATUS_CLUSTER_UP=false
  fi
}

# status_check_pod <label selector>, eg app=polaris: the subject's pod
status_check_pod() {
  status_need_cluster "$STATUS_SUBJECT pod" || return 0
  # '|| true' as kubectl exits non-zero when there is no such pod - the very case to report.
  local status ready
  status=$(status_kubectl get pods -l "$1" -o jsonpath='{.items[0].status.phase}' 2>/dev/null || true)
  ready=$(status_kubectl get pods -l "$1" -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || true)
  if [ "$status" = "Running" ] && [ "$ready" = "true" ]; then
    echo "- $STATUS_SUBJECT pod is Running and Ready"
  else
    status_failed "$STATUS_SUBJECT pod status='$status' ready='$ready'"
  fi
}

# status_check_ingress <ingress name> <ip|hostname> <what to call it>: an ingress gets an IP or a hostname
# depending on the cloud's load balancer.
status_check_ingress() {
  status_need_cluster "ingress" || return 0
  local address
  address=$(status_kubectl get ingress "$1" -o jsonpath="{.status.loadBalancer.ingress[0].$2}" 2>/dev/null || true)
  if [ -n "$address" ]; then
    echo "- Ingress has $3 $address"
  else
    status_failed "Ingress $1 has no $3"
  fi
}

# status_check_tls <secret name>: where TLS ends in the cluster
status_check_tls() {
  status_need_cluster "TLS secret" || return 0
  local crt
  crt=$(status_kubectl get secret "$1" -o jsonpath='{.data.tls\.crt}' 2>/dev/null || true)
  if [ -n "$crt" ]; then
    echo "- TLS secret $1 present"
  else
    status_failed "TLS secret $1 missing or empty"
  fi
}

# Gets an access token from the Keycloak realm polaris, by the client credentials of the Polaris admin (POLARIS_ID and
# POLARIS_SECRET, as for check-oidc --token), for the authenticated checks, writing it to $STATUS_DIR/token, and prints
# one line: "OK|<POLARIS_ID>", "SKIPPED|<why>" or "FAILED|<message>".  Meant to be run in a subshell: it sources the
# secrets, which must not be left in the script's environment, nor exported to the commands it runs later.  The
# secret and the token only ever reach curl through files in $STATUS_DIR (a 700 dir, removed at exit), as an argument
# would show in ps.
# status_keycloak_token <cloud>, eg aws, whose secret properties hold the admin's credentials
status_keycloak_token() {
  local cloud="$1" polaris_id secret oidc code token
  set +u # the properties may well be unset, and are then reported as such below
  source "$AFON_ROOT/scripts/config/secret-source.sh" "$cloud" >/dev/null 2>&1 || true
  polaris_id="$POLARIS_ID"
  secret="$POLARIS_SECRET"
  oidc="$OIDC_DOMAIN_NAME"
  if [ -z "$polaris_id" ] || [ -z "$secret" ]; then
    echo "SKIPPED|POLARIS_ID or POLARIS_SECRET is not set"
    return 0
  fi
  if [ -z "$oidc" ]; then
    echo "SKIPPED|OIDC_DOMAIN_NAME is not set"
    return 0
  fi
  printf '%s' "$secret" > "$STATUS_DIR/client_secret"
  code=$(curl -s -m 15 -o "$STATUS_DIR/token_response" -w "%{http_code}" \
              --data-urlencode "grant_type=client_credentials" \
              --data-urlencode "client_id=$polaris_id" \
              --data-urlencode "client_secret@$STATUS_DIR/client_secret" \
              "https://$oidc/realms/polaris/protocol/openid-connect/token") || true # prints 000 when it can't connect
  rm -f "$STATUS_DIR/client_secret"
  case "$code" in
    200)
      token=$(sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p' "$STATUS_DIR/token_response")
      ;;
    000)
      echo "FAILED|Keycloak at $oidc did not answer the token request (DNS or connection failed)"
      return 0
      ;;
    400|401)
      echo "FAILED|Keycloak rejected the credentials of $polaris_id (HTTP $code)"
      return 0
      ;;
    *)
      echo "FAILED|Keycloak at $oidc gave HTTP $code to the token request"
      return 0
      ;;
  esac
  rm -f "$STATUS_DIR/token_response"
  if [ -z "$token" ]; then
    echo "FAILED|Keycloak answered the token request for $polaris_id with HTTP 200 but no access token"
    return 0
  fi
  printf '%s' "$token" > "$STATUS_DIR/token"
  echo "OK|$polaris_id"
}

# Prints the verdict and ends the script: exit 0, as the status was established either way.
status_report() {
  echo ""
  if [ "$STATUS_OK" = "true" ]; then
    echo "$STATUS_SUBJECT is deployed and serving."
  else
    echo "$STATUS_SUBJECT is NOT deployed correctly - see FAILED lines above."
  fi
  status_exit 0
}
