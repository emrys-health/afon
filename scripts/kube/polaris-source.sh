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
#   6. a real HTTPS request through DNS -> load balancer -> ingress -> pod, without credentials
#   7. the same as the Polaris admin: a token for the admin principal (POLARIS_ID and POLARIS_SECRET,
#      as for show-oidc-token) from Keycloak, then the list of the catalogs Polaris shows it
# The kubectl layers say where a failure is (eg no nodes, so a Pending pod); the requests are what
# prove Polaris really serves.  A healthy Polaris answers the anonymous request 401, which shows it is
# up and enforces authentication; only the authenticated request shows the login path working, ie
# Keycloak issuing the token and Polaris accepting it.  A layer that needs a cluster is skipped when
# there is none to ask, and the authenticated request when Polaris did not answer or the admin
# credentials are not configured - each skip is a line of output, not a failure.
# Uses a throwaway kubeconfig, so your current kubectl context is left alone.  The admin secret and
# the token are never printed, nor put on a command line where ps would show them.
#
# The scripts report the status, so 'serving' and 'not serving' are both a successful run: the exit
# code is 0 whenever the status was established and 2 when it could not be verified, so that an
# expired login or a missing tool is never mistaken for Polaris being down.

POLARIS_OK=true
POLARIS_CLUSTER_UP=false
POLARIS_ANSWERING=false # whether Polaris answered the anonymous request
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
    200)
      echo "- Polaris is up and the catalog API is serving"
      POLARIS_ANSWERING=true
      ;;
    401)
      echo "- Polaris is up and refusing an anonymous caller"
      POLARIS_ANSWERING=true
      ;;
    000)
      polaris_failed "No HTTPS response from $POLARIS_DOMAIN (DNS or connection failed)"
      ;;
    *)
      polaris_failed "HTTPS gave HTTP $code - something answered, but it is not Polaris serving (eg a load balancer with no ready pod behind it)"
      ;;
  esac
}

# Does the authenticated request for polaris_check_authenticated, ie lists the catalogs the admin
# principal can see, and prints one line: "OK|<message>", "SKIPPED|<why>" or "FAILED|<message>".  Meant
# to be run in a subshell: it sources the secrets, which must not be left in the script's environment,
# nor exported to the commands it runs later.  The admin secret and the token only ever reach curl
# through files in $POLARIS_DIR (a 700 dir, removed at exit), as an argument would show in ps.
polaris_authenticated_request() {
  local cloud="$1" polaris_id secret oidc code token catalogs
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
  printf '%s' "$secret" > "$POLARIS_DIR/client_secret"
  code=$(curl -s -m 15 -o "$POLARIS_DIR/token_response" -w "%{http_code}" \
              --data-urlencode "grant_type=client_credentials" \
              --data-urlencode "client_id=$polaris_id" \
              --data-urlencode "client_secret@$POLARIS_DIR/client_secret" \
              "https://$oidc/realms/polaris/protocol/openid-connect/token") || true # prints 000 when it can't connect
  rm -f "$POLARIS_DIR/client_secret"
  case "$code" in
    200)
      token=$(sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p' "$POLARIS_DIR/token_response")
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
  rm -f "$POLARIS_DIR/token_response"
  if [ -z "$token" ]; then
    echo "FAILED|Keycloak answered the token request for $polaris_id with HTTP 200 but no access token"
    return 0
  fi

  printf 'Authorization: Bearer %s' "$token" > "$POLARIS_DIR/authorization"
  code=$(curl -s -m 15 -o "$POLARIS_DIR/catalogs_response" -w "%{http_code}" -H "@$POLARIS_DIR/authorization" \
              "https://$POLARIS_DOMAIN/api/management/v1/catalogs") || true
  rm -f "$POLARIS_DIR/authorization"
  case "$code" in
    200)
      # the names of the catalogs in the JSON reply, as a comma separated list
      catalogs=$(grep -o '"name" *: *"[^"]*"' "$POLARIS_DIR/catalogs_response" | sed 's/.*: *"\(.*\)"/\1/' | tr '\n' ',' | sed 's/,$//; s/,/, /g')
      if [ -n "$catalogs" ]; then
        echo "OK|Polaris is up and the catalog API is serving: catalogs available to $polaris_id: $catalogs"
      else
        echo "OK|Polaris is up and the catalog API is serving: no catalogs available to $polaris_id"
      fi
      ;;
    401) echo "FAILED|Polaris rejected the access token of $polaris_id (HTTP 401)" ;;
    403) echo "FAILED|$polaris_id is not allowed to list the catalogs (HTTP 403)" ;;
    000) echo "FAILED|No HTTPS response from $POLARIS_DOMAIN to the authenticated request (DNS or connection failed)" ;;
    *)   echo "FAILED|The authenticated request gave HTTP $code from $POLARIS_DOMAIN" ;;
  esac
}

# polaris_check_authenticated <cloud>, eg aws, whose secret properties hold the admin's credentials: the
# request only a logged-in principal can make, which is what shows the login path working (Keycloak issuing
# the token, Polaris accepting it), as opposed to polaris_check_https, which shows Polaris up and
# enforcing authentication.  Only the API answering matters: no catalogs at all is still a success, eg
# on a stack that is deployed but not yet set up.
polaris_check_authenticated() {
  if [ "$POLARIS_ANSWERING" != "true" ]; then
    echo "- Skipping the authenticated check: Polaris did not answer"
    return 0
  fi
  local result
  result=$(polaris_authenticated_request "$1")
  case "${result%%|*}" in
    OK)      echo "- ${result#*|}" ;;
    SKIPPED) echo "- Skipping the authenticated check: ${result#*|}" ;;
    FAILED)  polaris_failed "${result#*|}" ;;
    *)       polaris_failed "The authenticated check failed unexpectedly" ;;
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
