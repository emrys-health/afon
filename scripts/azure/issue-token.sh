#!/usr/bin/env bash
# A throwaway OIDC issuer of your own, to try the federated login of the app registration, see AZURE_WORKLOAD_IDENTITY, from a machine that is
# neither an Azure DevOps Services pipeline nor a Kubernetes cluster, eg your laptop.  It is for testing only: whoever has its key can act as
# the app, wherever the federated credential trusts its issuer.
#
# azure-issue-token init <issuer-url> [<subject>]
#   Creates the key, and the two files that Entra ID reads to check the tokens, in the directory below.  <issuer-url> is where you will publish them,
#   over public HTTPS, eg of an Azure Storage static website or GitHub Pages: the files hold only the public key.  <subject> is what the tokens
#   say they are for, by default deployer-test.  Prints what to publish, and the command for the admin to trust the issuer.
# azure-issue-token test-token
#   Signs a token of ten minutes with the key, and prints the path of the file that has it, which is AZURE_FEDERATED_TOKEN_FILE for azure-login-app:
#     export AZURE_FEDERATED_TOKEN_FILE=$(afon-cli.sh azure-issue-token test-token)
#
# The directory is AZURE_ISSUE_TOKEN_DIR, by default $AFON_HOME/azure-issue-token.  Requires openssl, jq and xxd.
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
DIR="${AZURE_ISSUE_TOKEN_DIR:-$AFON_HOME/azure-issue-token}"
AUDIENCE="api://AzureADTokenExchange"
KID="test-1"
ACTION="${1:-}"

usage(){
  echo "Usage: azure-issue-token init <issuer-url> [<subject>] | test-token" >&2
}

for TOOL in openssl jq xxd; do
  command -v "$TOOL" >/dev/null 2>&1 || { echo "-- $TOOL is required" >&2; exit 1; }
done

# base64url, as JWTs have it, of stdin
b64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

case "$ACTION" in
  init)
    ISSUER="${2:-}"
    SUBJECT="${3:-deployer-test}"
    if [ -z "$ISSUER" ]; then
      usage
      exit 1
    fi
    ISSUER="${ISSUER%/}"
    case "$ISSUER" in
      https://*) ;;
      *) echo "-- The issuer must be an https:// URL: Entra ID reads its files over HTTPS" >&2; exit 1 ;;
    esac
    if [ -e "$DIR/issuer.key" ]; then
      echo "-- There is a key already in $DIR: delete the directory to start again" >&2
      exit 1
    fi
    (umask 077 && mkdir -p "$DIR") || exit 1
    (umask 077 && openssl genrsa -out "$DIR/issuer.key" 2048 2>/dev/null) || exit 1
    # the public key as the JWK, whose modulus n is that of the key, and whose exponent is 65537, which is AQAB
    MODULUS="$(openssl rsa -in "$DIR/issuer.key" -noout -modulus | cut -d= -f2 | xxd -r -p | b64url)"
    jq -n --arg n "$MODULUS" --arg kid "$KID" \
      '{keys:[{kty:"RSA",use:"sig",alg:"RS256",kid:$kid,n:$n,e:"AQAB"}]}' > "$DIR/jwks.json"
    jq -n --arg i "$ISSUER" \
      '{issuer:$i,jwks_uri:($i+"/jwks.json"),response_types_supported:["id_token"],subject_types_supported:["public"],id_token_signing_alg_values_supported:["RS256"]}' \
      > "$DIR/openid-configuration"
    echo "$ISSUER" > "$DIR/issuer"
    echo "$SUBJECT" > "$DIR/subject"
    echo "- Created the key and the files in $DIR"
    echo "- Publish, over public HTTPS:"
    echo "-- $DIR/openid-configuration  as  $ISSUER/.well-known/openid-configuration"
    echo "-- $DIR/jwks.json             as  $ISSUER/jwks.json"
    echo "- Then ask the admin to trust the issuer for the app, with the app ID in place of <APP_ID>:"
    echo "afon-cli.sh azure-identity-admin afon-test-machine '$ISSUER' '$SUBJECT' <APP_ID>"
    echo "- And get a token with: export AZURE_FEDERATED_TOKEN_FILE=\$(afon-cli.sh azure-issue-token test-token)"
    ;;

  test-token)
    if [ ! -f "$DIR/issuer.key" ] || [ ! -f "$DIR/issuer" ] || [ ! -f "$DIR/subject" ]; then
      echo "-- There is no issuer in $DIR: run azure-issue-token init <issuer-url> first" >&2
      exit 1
    fi
    ISSUER="$(cat "$DIR/issuer")"
    SUBJECT="$(cat "$DIR/subject")"
    NOW="$(date +%s)"
    HEADER="$(jq -nc --arg kid "$KID" '{alg:"RS256",typ:"JWT",kid:$kid}' | b64url)"
    PAYLOAD="$(jq -nc --arg iss "$ISSUER" --arg sub "$SUBJECT" --arg aud "$AUDIENCE" --argjson now "$NOW" \
      '{iss:$iss,sub:$sub,aud:$aud,iat:$now,nbf:$now,exp:($now+600)}' | b64url)"
    SIGNATURE="$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -sign "$DIR/issuer.key" | b64url)"
    (umask 077 && printf '%s.%s.%s' "$HEADER" "$PAYLOAD" "$SIGNATURE" > "$DIR/token.jwt") || exit 1
    echo "- Signed a token of ten minutes for the subject $SUBJECT" >&2
    echo "$DIR/token.jwt"
    ;;

  *)
    usage
    exit 1
    ;;
esac
