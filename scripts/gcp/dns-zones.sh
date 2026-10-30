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

dns_check_cloud_config gcp || exit 1

for DOMAIN in $(dns_cloud_domains gcp); do
  show_dns_zone GCP "$DOMAIN" "$(dns_zone_nameservers gcp $DOMAIN)"
done
