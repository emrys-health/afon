# Shared by the <cloud>/polaris.sh status scripts, which source it after afon-source.sh, so that the
# three clouds report Polaris in exactly the same way.  Not run on its own.
#
# Adds to the layers of kube/status-source.sh the HTTPS requests to Polaris:
#   - without credentials: a healthy Polaris answers 401, which shows it is up and enforces authentication;
#   - as the Polaris admin: a token for the admin principal (POLARIS_ID and POLARIS_SECRET, as for
#     show-oidc-token) from Keycloak, then the list of the catalogs Polaris shows it.  Only this request
#     shows the login path working, ie Keycloak issuing the token and Polaris accepting it.  It is skipped
#     when Polaris did not answer or the admin credentials are not configured.

STATUS_SUBJECT=Polaris
source $AFON_ROOT/scripts/kube/status-source.sh

POLARIS_ANSWERING=false # whether Polaris answered the anonymous request

polaris_check_https() {
  local code
  code=$(curl -s -o /dev/null -m 15 -w "%{http_code}" "https://$STATUS_DOMAIN/api/catalog/v1/config") || true # prints 000 when it can't connect
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
      status_failed "No HTTPS response from $STATUS_DOMAIN (DNS or connection failed)"
      ;;
    *)
      status_failed "HTTPS gave HTTP $code - something answered, but it is not Polaris serving (eg a load balancer with no ready pod behind it)"
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
              "https://$STATUS_DOMAIN/api/management/v1/catalogs") || true
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
    000) echo "FAILED|No HTTPS response from $STATUS_DOMAIN to the authenticated request (DNS or connection failed)" ;;
    *)   echo "FAILED|The authenticated request gave HTTP $code from $STATUS_DOMAIN" ;;
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
