#!/usr/bin/env bash
# Makes the key pair of a client of a project, its TRE client or its Git client: the client authenticates to the central identity with a
# private key, and the identity is given the public key, as a JWK, see docs/projects.md.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <project> <client> [--private-key <file>] [--force]"
  echo "Generates an RSA key pair (2048 bits) for the client of the project, <project>-<client>, and writes:"
  echo "  the public key, as a JWK, to \$AFON_HOME/projects/<project>/keys/<client>.jwk, which manage-identity create gives the identity;"
  echo "  the private key, as a PEM readable by its owner only, to --private-key, by default \$AFON_HOME/keys/<project>/<client>.pem, outside"
  echo "  the project's dir, as Afon never reads it: the TRE operator keeps it in the secret store of the TRE's cloud, and deletes this copy."
  echo "<project> is the id of the project, a lower-case DNS label of at most 30 characters, eg xyz-yyyy-nnnn."
  echo "<client> is tre, the client of the project's TRE, or git, the client of its own Git, which a project has only if its file has stacks.git."
  echo "It stops if either file exists already, as a client has the one key that the identity was given, unless --force replaces both."
  echo "Needs openssl, xxd and base64."
}

PROJECT=""
CLIENT=""
PRIVATE_KEY=""
FORCE=false
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --force) FORCE=true; shift ;;
    --private-key)
      if [ $# -lt 2 ]; then echo "- --private-key needs a file"; usage; exit 1; fi
      PRIVATE_KEY="$2"; shift 2 ;;
    -*) echo "- Unknown option '$1'"; usage; exit 1 ;;
    *)
      if [ -z "$PROJECT" ]; then
        PROJECT="$1"
      elif [ -z "$CLIENT" ]; then
        CLIENT="$1"
      else
        echo "- Only a project and a client are given, not also '$1'"; usage; exit 1
      fi
      shift ;;
  esac
done

if [ -z "$PROJECT" ] || [ -z "$CLIENT" ]; then
  usage; exit 1
fi
case "$CLIENT" in
  tre|git) ;;
  *) echo "- '$CLIENT' is not a client of a project: tre or git"; usage; exit 1 ;;
esac
# the id names a dir, so it is checked as ProjectId does, which also keeps it from naming a path
if [ "${#PROJECT}" -gt 30 ] || ! [[ "$PROJECT" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]]; then
  echo "- '$PROJECT' is not a project id: a lower-case DNS label of at most 30 characters, eg xyz-yyyy-nnnn"
  exit 1
fi
for TOOL in openssl xxd base64; do
  if ! command -v "$TOOL" > /dev/null 2>&1; then
    echo "- $TOOL is needed and is not installed"
    exit 1
  fi
done

PROJECT_DIR="$AFON_HOME/projects/$PROJECT"
PUBLIC_KEY="$PROJECT_DIR/keys/$CLIENT.jwk"
PRIVATE_KEY="${PRIVATE_KEY:-$AFON_HOME/keys/$PROJECT/$CLIENT.pem}"

if [ "$FORCE" != "true" ]; then
  for FILE in "$PUBLIC_KEY" "$PRIVATE_KEY"; do
    if [ -e "$FILE" ]; then
      echo "- $FILE exists already: the $CLIENT client has the key that the identity was given from it, so it is not replaced"
      echo "  Use --force to replace both, then make the project in the identity again: manage-identity create $PROJECT"
      exit 1
    fi
  done
fi

# base64 of the bytes, as the URL-safe alphabet without padding, which a JWK has
base64url() {
  base64 | tr -d '\n=' | tr '+/' '-_'
}

mkdir -p "$(dirname "$PUBLIC_KEY")" "$(dirname "$PRIVATE_KEY")"
# the private key is made readable by its owner only, from its creation
(
  umask 077
  openssl genrsa -out "$PRIVATE_KEY" 2048 2> /dev/null
)
chmod 600 "$PRIVATE_KEY"

# the modulus, as the hex that openssl prints it, and the exponent, which openssl genrsa makes 65537, ie AQAB
MODULUS_HEX="$(openssl rsa -in "$PRIVATE_KEY" -noout -modulus 2> /dev/null | sed 's/^Modulus=//')"
if [ -z "$MODULUS_HEX" ]; then
  echo "- Could not read the modulus of the key that openssl made"
  rm -f "$PRIVATE_KEY"
  exit 1
fi
MODULUS="$(printf '%s' "$MODULUS_HEX" | xxd -r -p | base64url)"
printf '{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s-%s","n":"%s","e":"AQAB"}\n' "$PROJECT" "$CLIENT" "$MODULUS" > "$PUBLIC_KEY"

echo "- Public key of the $CLIENT client of $PROJECT, $PROJECT-$CLIENT: $PUBLIC_KEY"
echo "- Private key: $PRIVATE_KEY (readable by its owner only)"
echo "  Keep the private key in the secret store of the TRE's cloud, and delete this copy: Afon never reads it."
if [ ! -f "$PROJECT_DIR/project.yaml" ]; then
  echo "- There is no $PROJECT_DIR/project.yaml yet: write it, see docs/projects.md"
fi
echo "- Then: afon-cli.sh manage-identity create $PROJECT"
