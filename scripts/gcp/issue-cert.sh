source $AFON_ROOT/scripts/config/afon-source.sh gcp

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [acme]"
  echo "Issue and stores in GCP Secret Manager the cert for the $GCP_POLARIS_DOMAIN_NAME."
  echo "The supported issuers are:"
  echo " - acme via Let's Encrypt (free - default)"
}

ISSUER="${1:-acme}"

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"
ACME_HOME="${ACME_HOME:-$AFON_HOME/acme}"
# The release of acme.sh fetched, rather than its master branch, which can be broken at any commit
ACME_VERSION="${ACME_VERSION:-3.1.6}"

require_domain_name() {
  if [ -z "$GCP_POLARIS_DOMAIN_NAME" ]; then
    echo "- GCP_POLARIS_DOMAIN_NAME is blank - set it in afon-gcp.properties before caching a cert"
    exit 1
  fi
}

# Fetches the acme.sh script directly rather than running its install.sh - we invoke it
# by explicit path with --config-home every time, so install.sh's cron job and shell
# profile setup (and its own argument parsing, which chokes on --home in some versions)
# aren't needed.
install_acme() {
  if [ ! -x "$ACME_HOME/acme.sh" ]; then
    echo "- Fetching acme.sh to $ACME_HOME"
    mkdir -p "$ACME_HOME"
    FETCH_DIR="$(mktemp -d)"
    curl -sfL "https://github.com/acmesh-official/acme.sh/archive/refs/tags/$ACME_VERSION.tar.gz" | tar xz -C "$FETCH_DIR" || exit 1
    cp -R "$FETCH_DIR/acme.sh-$ACME_VERSION/." "$ACME_HOME/" || exit 1
    chmod +x "$ACME_HOME/acme.sh"
    rm -rf "$FETCH_DIR"
  fi
}

# Issues (or renews, idempotently) the domain's cert directly against Let's Encrypt via DNS01.
# Free, but subject to letsencrypt's 90-day validity and usage rate limits.
# acme.sh's dns_gcloud hook uses the active gcloud account (and CLOUDSDK_CORE_PROJECT) to
# create/delete the _acme-challenge TXT record in the project's Cloud DNS zone, so that
# account needs the DNS Administrator role on it.
issue_via_acme() {
  install_acme

  export CLOUDSDK_CORE_PROJECT=$PROJECT_ID

  echo "- Requesting/renewing $GCP_POLARIS_DOMAIN_NAME via Let's Encrypt (DNS-01)"
  # The CLI runs scripts under set -e, so capture the exit code without aborting on it
  ACME_EXIT=0
  "$ACME_HOME/acme.sh" --config-home "$ACME_HOME" --issue --dns dns_gcloud \
    -d "$GCP_POLARIS_DOMAIN_NAME" \
    --keylength 2048 \
    --server letsencrypt \
    --accountemail "admin@$GCP_POLARIS_DOMAIN_NAME" || ACME_EXIT=$?
  # acme.sh exits 2 for "not yet due for renewal" - not an error, the cached cert is
  # still valid and gets re-pushed below unchanged.
  if [ $ACME_EXIT -ne 0 ] && [ $ACME_EXIT -ne 2 ]; then
    echo "- acme.sh issue failed (exit $ACME_EXIT)"
    exit 1
  fi

  CERT_DIR="$ACME_HOME/$GCP_POLARIS_DOMAIN_NAME"
  # AFON_ROOT is a plain shell variable in the CLI, so pass it explicitly to the child script
  AFON_ROOT="$AFON_ROOT" "$AFON_ROOT/scripts/gcp/secrets.sh" store "$CERT_DIR/fullchain.cer" "$CERT_DIR/$GCP_POLARIS_DOMAIN_NAME.key" || exit 1
}

require_domain_name

case "$ISSUER" in
  acme)
    issue_via_acme
    ;;

  *)
    usage
    exit 1
    ;;
esac
