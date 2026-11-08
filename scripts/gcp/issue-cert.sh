#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh gcp

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [--check] [acme]"
  echo "With --check, issues nothing: reports whether the cert of each domain name is cached in Secret Manager, and succeeds only if all are."
  echo "A stack restores the cert from there, rather than asking Let's Encrypt anew on each deploy, which allows only 5 certs a week of"
  echo "the same names."
  echo "Issue and stores in GCP Secret Manager the cert of each domain name that is configured, ie not blank, of"
  echo "GCP_POLARIS_DOMAIN_NAME, GCP_JUPYTER_DOMAIN_NAME and OIDC_DOMAIN_NAME."
  echo "The supported issuers are:"
  echo " - acme via Let's Encrypt (free - default)"
}

CHECK=false
if [ "${1:-}" == "--check" ]; then
  CHECK=true
  shift
fi
ISSUER="${1:-acme}"

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"
ACME_HOME="${ACME_HOME:-$AFON_HOME/acme}"
# The release of acme.sh fetched, rather than its master branch, which can be broken at any commit
ACME_VERSION="${ACME_VERSION:-3.1.6}"

# The domain names to issue the certs of, those configured, without duplicates
DOMAIN_NAMES=""
for DOMAIN_PROPERTY in GCP_POLARIS_DOMAIN_NAME GCP_JUPYTER_DOMAIN_NAME OIDC_DOMAIN_NAME; do
  DOMAIN_NAME="${!DOMAIN_PROPERTY:-}"
  if [ -z "$DOMAIN_NAME" ]; then
    echo "- $DOMAIN_PROPERTY is not set, skipping its cert"
  else
    case " $DOMAIN_NAMES " in
      *" $DOMAIN_NAME "*) ;;
      *) DOMAIN_NAMES="$DOMAIN_NAMES $DOMAIN_NAME" ;;
    esac
  fi
done
# Reports whether the cert of each domain name is cached in Secret Manager, under the secret names the restore step of the stack reads.
# Succeeds if all are, or if the secrets are not enabled, GCP_SECRETS_ENABLED, as then the certs are not cached by design.
check_cached_certs() {
  local DOMAIN KEBAB MISSING=0
  if [ "${GCP_SECRETS_ENABLED:-}" != "true" ]; then
    echo "- GCP_SECRETS_ENABLED is not true: the certs are not cached, each deploy asks Let's Encrypt for them"
    return 0
  fi
  for DOMAIN in $DOMAIN_NAMES; do
    KEBAB="${DOMAIN//./-}"
    if gcloud secrets describe "${KEBAB}-tls-crt" --project="$PROJECT_ID" > /dev/null 2>&1 \
         && gcloud secrets describe "${KEBAB}-tls-key" --project="$PROJECT_ID" > /dev/null 2>&1; then
      echo "- $DOMAIN: cert cached in Secret Manager of $PROJECT_ID (${KEBAB}-tls-crt)"
    else
      echo "- $DOMAIN: cert NOT cached in Secret Manager of $PROJECT_ID"
      MISSING=1
    fi
  done
  if [ $MISSING -ne 0 ]; then
    echo "- Issue and cache the missing certs with: gcp-issue-cert acme  (Let's Encrypt allows 5 certs a week of the same names)"
  fi
  return $MISSING
}

require_domain_names() {
  if [ -z "$DOMAIN_NAMES" ]; then
    echo "- No domain name is configured, nothing to issue: set them in afon-gcp.properties before caching a cert"
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

  for DOMAIN_NAME in $DOMAIN_NAMES; do
    echo "- Requesting/renewing $DOMAIN_NAME via Let's Encrypt (DNS-01)"
    # The CLI runs scripts under set -e, so capture the exit code without aborting on it
    ACME_EXIT=0
    "$ACME_HOME/acme.sh" --config-home "$ACME_HOME" --issue --dns dns_gcloud \
      -d "$DOMAIN_NAME" \
      --keylength 2048 \
      --server letsencrypt \
      --accountemail "admin@$DOMAIN_NAME" || ACME_EXIT=$?
    # acme.sh exits 2 for "not yet due for renewal" - not an error, the cached cert is
    # still valid and gets re-pushed below unchanged.
    if [ $ACME_EXIT -ne 0 ] && [ $ACME_EXIT -ne 2 ]; then
      echo "- acme.sh issue failed for $DOMAIN_NAME (exit $ACME_EXIT)"
      exit 1
    fi

    CERT_DIR="$ACME_HOME/$DOMAIN_NAME"
    # AFON_ROOT is a plain shell variable in the CLI, so pass it explicitly to the child script
    AFON_ROOT="$AFON_ROOT" "$AFON_ROOT/scripts/gcp/secrets.sh" store "$CERT_DIR/fullchain.cer" "$CERT_DIR/$DOMAIN_NAME.key" "$DOMAIN_NAME" || exit 1
  done
}

require_domain_names

if [ "$CHECK" == "true" ]; then
  if check_cached_certs; then exit 0; else exit 1; fi
fi

case "$ISSUER" in
  acme)
    issue_via_acme
    ;;

  *)
    usage
    exit 1
    ;;
esac
