# The config is read, not sourced as the other scripts do, as this script is what repairs it: the property used to be written as a PEM on
# one line, with spaces, which the shell cannot source (it runs the words after the first as a command), and sourcing it would stop here.
CLOUD="${1:-}"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <aws|azure|gcp> [--print]"
  echo "Sets <CLOUD>_COSIGN_PUBLIC_KEY in \$AFON_HOME/config/afon-<cloud>.properties to the public key that Kyverno verifies the signatures"
  echo "of the images with, see docs/stacks.md, as its base64, a single word, since the scripts source that file."
  echo "The key is that of <CLOUD>_COSIGN_KEY if it is set, a file or a KMS key as cosign takes it (a file's password is the env COSIGN_PASSWORD)."
  echo "Otherwise a key pair is generated in \$AFON_HOME/cosign/<cloud> (COSIGN_KEY_DIR), once, and reused, and since the policy only needs the"
  echo "public key, which is enough to see in the policy reports the images that are not signed, the private key is without a password unless"
  echo "COSIGN_PASSWORD is set: it can then sign images, with <CLOUD>_COSIGN_KEY set to it, and is not to be shared."
  echo "The property is replaced, or added, in the file, which is first copied to <file>.bak."
  echo "--print only prints the line that the property is set to."
}

case "$CLOUD" in
  aws|azure|gcp) ;;
  -h|--help) usage; exit 0 ;;
  *) usage; exit 1 ;;
esac

PRINT=false
case "${2:-}" in
  --print) PRINT=true ;;
  "") ;;
  *) usage; exit 1 ;;
esac

CLOUD_UPPER="$(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')"
PROPERTY="${CLOUD_UPPER}_COSIGN_PUBLIC_KEY"
KEY_PROPERTY="${CLOUD_UPPER}_COSIGN_KEY"
PROPS="$AFON_HOME/config/afon-$CLOUD.properties"
KEY_DIR="${COSIGN_KEY_DIR:-$AFON_HOME/cosign/$CLOUD}"

if [ ! -f "$PROPS" ]; then
  echo "- $PROPS does not exist: run afon-cli.sh config-init"
  exit 1
fi
# The value of a property of the file, the last if it is there more than once, or blank
property() {
  sed -n "s/^$1=//p" "$PROPS" | tail -1
}
COSIGN_KEY="$(property "$KEY_PROPERTY")"

if ! command -v cosign >/dev/null; then
  echo "- cosign is not installed: run afon-cli.sh local-setup"
  exit 1
fi

# The public key as a PEM
if [ -n "$COSIGN_KEY" ]; then
  echo "- Using the key $KEY_PROPERTY: $COSIGN_KEY" >&2
  # no input, so that cosign fails, rather than waits, if it asks for the password of the key
  PEM="$(cosign public-key --key "$COSIGN_KEY" </dev/null)" || {
    echo "- Cannot get the public key of $COSIGN_KEY: if it is a file with a password, set COSIGN_PASSWORD (empty if it has none)"
    exit 1
  }
else
  if [ ! -f "$KEY_DIR/cosign.key" ] || [ ! -f "$KEY_DIR/cosign.pub" ]; then
    echo "- Generating a key pair in $KEY_DIR" >&2
    mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
    # an empty password, unless COSIGN_PASSWORD is set, as cosign would ask for one
    (cd "$KEY_DIR" && COSIGN_PASSWORD="${COSIGN_PASSWORD-}" cosign generate-key-pair </dev/null >&2) || exit 1
    chmod 600 "$KEY_DIR/cosign.key"
  else
    echo "- Using the key pair in $KEY_DIR" >&2
  fi
  PEM="$(cat "$KEY_DIR/cosign.pub")"
fi

case "$PEM" in
  *"-----BEGIN PUBLIC KEY-----"*"-----END PUBLIC KEY-----"*) ;;
  *) echo "- Not a PEM public key: $PEM"; exit 1 ;;
esac

# The base64 of the key alone, a single word with no space, as the shell sources this file too, and a PEM has spaces in its first line
LINE="$PROPERTY=$(printf '%s\n' "$PEM" | grep -v -- '-----' | tr -d '[:space:]')"

if [ "$PRINT" == true ]; then
  echo "$LINE"
  exit 0
fi

if grep -qxF -- "$LINE" "$PROPS"; then
  echo "- $PROPERTY is already set to this key in $PROPS"
else
  BACKUP="$PROPS.bak"
  [ -e "$BACKUP" ] && BACKUP="$PROPS.bak.$(date +%Y%m%d%H%M%S)"
  cp "$PROPS" "$BACKUP" || exit 1
  # the line goes through the environment, as awk would turn the \n of -v into newlines
  LINE="$LINE" PROPERTY="$PROPERTY" awk '
    index($0, ENVIRON["PROPERTY"] "=") == 1 { print ENVIRON["LINE"]; done = 1; next }
    { print }
    END { if (!done) print ENVIRON["LINE"] }' "$BACKUP" > "$PROPS" || exit 1
  echo "- $PROPERTY set in $PROPS (copy: $BACKUP)"
fi
if [ -z "$COSIGN_KEY" ]; then
  echo "- The private key is $KEY_DIR/cosign.key: set $KEY_PROPERTY to it to sign images with it"
fi
case "$CLOUD" in
  gcp) echo "- No GCP stack deploys Kyverno yet, so nothing reads $PROPERTY on GCP, but docker-image --sign does not need it" ;;
  *) echo "- Check the stack with: afon-cli.sh validate-stack $CLOUD-full" ;;
esac
