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

if [ -z "${AZURE_RESOURCE_GROUP:-}" ]; then
  echo "- AZURE_RESOURCE_GROUP is not set - set it in afon-azure.properties"
  exit 1
fi

for DOMAIN in $(dns_unique_domains "${AZURE_POLARIS_DOMAIN_NAME:-}" "${AZURE_JUPYTER_DOMAIN_NAME:-}" "${OIDC_DOMAIN_NAME:-}"); do
  NAMESERVERS="$(az network dns zone show --name $DOMAIN --resource-group $AZURE_RESOURCE_GROUP --query 'nameServers' --output tsv \
                   2>/dev/null || true)"
  show_dns_zone Azure "$DOMAIN" "$NAMESERVERS"
done
