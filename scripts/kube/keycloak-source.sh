# Shared by the <cloud>/keycloak.sh status scripts, at present only aws/keycloak.sh, which source it after
# afon-source.sh.  Not run on its own.
#
# Adds to the layers of kube/status-source.sh the HTTPS requests to KeyCloak:
#   - without credentials: the discovery document of the master realm, which KeyCloak always has, so its answering
#     shows KeyCloak up and serving, then that of the realm polaris, which the setup of the stack creates, so its
#     answering shows KeyCloak set up for Polaris;
#   - as the Polaris admin: a token for the admin principal (POLARIS_ID and POLARIS_SECRET, as for show-oidc-token)
#     from the realm polaris, which shows the setup's clients in place, ie what Polaris and its clients log in with.
#     It is skipped when the realm polaris did not answer or the admin credentials are not configured.

STATUS_SUBJECT=KeyCloak
source $AFON_ROOT/scripts/kube/status-source.sh

KEYCLOAK_ANSWERING=false # whether KeyCloak answered with its master realm
KEYCLOAK_REALM_FOUND=false # whether the realm polaris answered

# keycloak_realm_code <realm>: the HTTP code of the realm's discovery document, 000 when it can't connect
keycloak_realm_code() {
  curl -s -o /dev/null -m 15 -w "%{http_code}" "https://$STATUS_DOMAIN/realms/$1/.well-known/openid-configuration" || true
}

keycloak_check_https() {
  local code
  code=$(keycloak_realm_code master)
  case "$code" in
    200)
      echo "- KeyCloak is up and serving its master realm"
      KEYCLOAK_ANSWERING=true
      ;;
    000)
      status_failed "No HTTPS response from $STATUS_DOMAIN (DNS or connection failed)"
      ;;
    *)
      status_failed "HTTPS gave HTTP $code - something answered, but it is not KeyCloak serving (eg a load balancer with no ready pod behind it)"
      ;;
  esac
}

keycloak_check_realm() {
  if [ "$KEYCLOAK_ANSWERING" != "true" ]; then
    echo "- Skipping the realm check: KeyCloak did not answer"
    return 0
  fi
  local code
  code=$(keycloak_realm_code polaris)
  case "$code" in
    200)
      echo "- KeyCloak has the realm polaris"
      KEYCLOAK_REALM_FOUND=true
      ;;
    404)
      status_failed "KeyCloak has no realm polaris: it is deployed but not set up, eg with: afon-cli.sh manage-stack setup aws-keycloak"
      ;;
    *)
      status_failed "The realm polaris gave HTTP $code"
      ;;
  esac
}

# keycloak_check_authenticated <cloud>, eg aws, whose secret properties hold the admin's credentials: the token
# request only a configured client can make, which is what shows KeyCloak set up for Polaris and its clients to log in.
keycloak_check_authenticated() {
  if [ "$KEYCLOAK_REALM_FOUND" != "true" ]; then
    echo "- Skipping the authenticated check: KeyCloak has no realm polaris to ask"
    return 0
  fi
  local result
  result=$(status_keycloak_token "$1")
  rm -f "$STATUS_DIR/token"
  case "${result%%|*}" in
    OK)      echo "- KeyCloak issues access tokens to ${result#*|}" ;;
    SKIPPED) echo "- Skipping the authenticated check: ${result#*|}" ;;
    FAILED)  status_failed "${result#*|}" ;;
    *)       status_failed "The authenticated check failed unexpectedly" ;;
  esac
}
