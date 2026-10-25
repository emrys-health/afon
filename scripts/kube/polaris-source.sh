# Shared by the <cloud>/polaris.sh status scripts, which source it after afon-source.sh, so that the
# three clouds report Polaris in exactly the same way.  Not run on its own.
#
# Adds to the layers of kube/status-source.sh the HTTPS requests to Polaris:
#   - without credentials: a healthy Polaris answers 401, which shows it is up and enforces authentication;
#   - as the Polaris admin: a token for the admin principal (POLARIS_ID and POLARIS_SECRET, as for
#     check-oidc --token) from Keycloak, then the list of the catalogs Polaris shows it.  Only this request
#     shows the login path working, ie Keycloak issuing the token and Polaris accepting it.  It is skipped
#     when Polaris did not answer or the admin credentials are not configured.
#
# By default, or with POLARIS_ACCESS=port-forward, the requests go through a kubectl port-forward to the Polaris pod, as the setup
# steps do: it is then Polaris itself that is checked, without its ingress, and its DNS record and certificate.  With
# POLARIS_ACCESS=https they go through the public domain name, so they fail with a 403 from the ingress if it is restricted to
# other callers, see AWS_/AZURE_/GCP_POLARIS_ALLOWED_CIDRS.

STATUS_SUBJECT=Polaris
source $AFON_ROOT/scripts/kube/status-source.sh

POLARIS_ANSWERING=false # whether Polaris answered the anonymous request
POLARIS_URL=""          # where Polaris is reached, see polaris_connect
POLARIS_PORT=8181       # the port that the Polaris pods listen on
STATUS_FORWARD_PID=""   # the kubectl port-forward process, if any

# Sets POLARIS_URL, unless already set: the local URL of a kubectl port-forward to the Polaris pod, which is stopped when the script
# exits, or, with POLARIS_ACCESS=https, the public https URL of the domain name.  Reports FAILED if there is none.
polaris_connect() {
  if [ -n "$POLARIS_URL" ]; then
    return 0
  fi
  case "${POLARIS_ACCESS:-port-forward}" in
    https|HTTPS)
      POLARIS_URL="https://$STATUS_DOMAIN"
      echo "- Reaching Polaris through its domain name ($POLARIS_URL), as POLARIS_ACCESS=$POLARIS_ACCESS"
      ;;
    port-forward)
      status_need_cluster "port-forward" || return 0
      local pod log i port
      pod=$(status_kubectl get pods -l app=polaris -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
      if [ -z "$pod" ]; then
        status_failed "No Polaris pod to port-forward to"
        return 0
      fi
      log="$STATUS_DIR/port-forward.log"
      # a local port of ':<port>' is picked by kubectl, and reported in the log; no request timeout, as it is long-lived
      kubectl --kubeconfig "$STATUS_KUBECONFIG" port-forward "pod/$pod" ":$POLARIS_PORT" >"$log" 2>&1 &
      STATUS_FORWARD_PID=$!
      trap 'kill $STATUS_FORWARD_PID 2>/dev/null || true' EXIT
      for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        port=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) -> .*/\1/p' "$log" 2>/dev/null | head -1)
        if [ -n "$port" ]; then
          POLARIS_URL="http://localhost:$port"
          echo "- Reaching Polaris through a port-forward to pod $pod ($POLARIS_URL), as POLARIS_ACCESS=${POLARIS_ACCESS:-port-forward (the default)}"
          return 0
        fi
        if ! kill -0 "$STATUS_FORWARD_PID" 2>/dev/null; then
          break
        fi
        sleep 1
      done
      status_failed "Could not port-forward to pod $pod: $(head -c 300 "$log" 2>/dev/null)"
      ;;
    *)
      status_failed "POLARIS_ACCESS should be 'https' or 'port-forward', not '${POLARIS_ACCESS}'"
      ;;
  esac
}

polaris_check_https() {
  local code
  polaris_connect
  if [ -z "$POLARIS_URL" ]; then
    return 0 # already reported
  fi
  code=$(curl -s -o /dev/null -m 15 -w "%{http_code}" "$POLARIS_URL/api/catalog/v1/config") || true # prints 000 when it can't connect
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
      status_failed "No response from $POLARIS_URL (DNS or connection failed)"
      ;;
    *)
      status_failed "$POLARIS_URL gave HTTP $code - something answered, but it is not Polaris serving (eg a load balancer with no ready pod behind it)"
      ;;
  esac
}

# Does the authenticated request for polaris_check_authenticated, ie lists the catalogs the admin
# principal can see, and prints one line: "OK|<message>", "SKIPPED|<why>" or "FAILED|<message>".  Meant
# to be run in a subshell, as status_keycloak_token sources the secrets.
polaris_authenticated_request() {
  local result polaris_id code catalogs
  result=$(status_keycloak_token "$1")
  if [ "${result%%|*}" != "OK" ]; then
    echo "$result"
    return 0
  fi
  polaris_id="${result#*|}"
  printf 'Authorization: Bearer %s' "$(cat "$STATUS_DIR/token")" > "$STATUS_DIR/authorization"
  rm -f "$STATUS_DIR/token"
  code=$(curl -s -m 15 -o "$STATUS_DIR/catalogs_response" -w "%{http_code}" -H "@$STATUS_DIR/authorization" \
              "$POLARIS_URL/api/management/v1/catalogs") || true
  rm -f "$STATUS_DIR/authorization"
  case "$code" in
    200)
      # the names of the catalogs in the JSON reply, as a comma separated list
      catalogs=$(grep -o '"name" *: *"[^"]*"' "$STATUS_DIR/catalogs_response" | sed 's/.*: *"\(.*\)"/\1/' | tr '\n' ',' | sed 's/,$//; s/,/, /g')
      if [ -n "$catalogs" ]; then
        echo "OK|Polaris is up and the catalog API is serving: catalogs available to $polaris_id: $catalogs"
      else
        echo "OK|Polaris is up and the catalog API is serving: no catalogs available to $polaris_id"
      fi
      ;;
    401) echo "FAILED|Polaris rejected the access token of $polaris_id (HTTP 401)" ;;
    403) echo "FAILED|$polaris_id is not allowed to list the catalogs (HTTP 403)" ;;
    000) echo "FAILED|No response from $POLARIS_URL to the authenticated request (DNS or connection failed)" ;;
    *)   echo "FAILED|The authenticated request gave HTTP $code from $POLARIS_URL" ;;
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
    FAILED)  status_failed "${result#*|}" ;;
    *)       status_failed "The authenticated check failed unexpectedly" ;;
  esac
}
