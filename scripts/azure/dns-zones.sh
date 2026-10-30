source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/dns/zones-source.sh

usage(){
  echo "Usage: ${BASH_SOURCE[0]}"
  echo "Shows the nameservers of the DNS zone in AZURE_RESOURCE_GROUP of each domain name of Azure, ie AZURE_POLARIS_DOMAIN_NAME,"
  echo "AZURE_JUPYTER_DOMAIN_NAME and OIDC_DOMAIN_NAME, and whether the registrar delegates the domain to them, see"
  echo "docs/dns_config.md.  The zone is the one named after the domain name."
}

if [ -n "${1:-}" ]; then
  usage
  exit 1
fi

dns_check_cloud_config azure || exit 1

for DOMAIN in $(dns_cloud_domains azure); do
  show_dns_zone Azure "$DOMAIN" "$(dns_zone_nameservers azure $DOMAIN)"
done
