source $AFON_ROOT/scripts/config/afon-source.sh gcp
source $AFON_ROOT/scripts/dns/zones-source.sh

usage(){
  echo "Usage: ${BASH_SOURCE[0]}"
  echo "Shows the nameservers of the Cloud DNS managed zone in GCP_PROJECT_ID of each domain name of GCP, ie GCP_POLARIS_DOMAIN_NAME,"
  echo "GCP_JUPYTER_DOMAIN_NAME and OIDC_DOMAIN_NAME, and whether the registrar delegates the domain to them, see"
  echo "docs/dns_config.md.  The zone is named after the domain name with its dots replaced by dashes."
}

if [ -n "${1:-}" ]; then
  usage
  exit 1
fi

if [ -z "${GCP_PROJECT_ID:-}" ]; then
  echo "- GCP_PROJECT_ID is not set - set it in afon-gcp.properties"
  exit 1
fi

for DOMAIN in $(dns_unique_domains "${GCP_POLARIS_DOMAIN_NAME:-}" "${GCP_JUPYTER_DOMAIN_NAME:-}" "${OIDC_DOMAIN_NAME:-}"); do
  NAMESERVERS="$(gcloud dns managed-zones describe ${DOMAIN//./-} --project $GCP_PROJECT_ID --format='value(nameServers)' \
                   2>/dev/null || true)"
  show_dns_zone GCP "$DOMAIN" "$NAMESERVERS"
done
