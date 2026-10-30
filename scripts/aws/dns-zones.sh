source $AFON_ROOT/scripts/config/afon-source.sh aws
source $AFON_ROOT/scripts/dns/zones-source.sh

usage(){
  echo "Usage: ${BASH_SOURCE[0]}"
  echo "Shows the nameservers of the Route 53 hosted zone of each domain name of AWS, ie AWS_POLARIS_DOMAIN_NAME,"
  echo "AWS_JUPYTER_DOMAIN_NAME and OIDC_DOMAIN_NAME, and whether the registrar delegates the domain to them, see"
  echo "docs/dns_config.md.  The zone is the one named after the domain name; Route 53 is global, so it needs no region."
}

if [ -n "${1:-}" ]; then
  usage
  exit 1
fi

for DOMAIN in $(dns_cloud_domains aws); do
  show_dns_zone AWS "$DOMAIN" "$(dns_zone_nameservers aws $DOMAIN)"
done
