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

for DOMAIN in $(dns_unique_domains "${AWS_POLARIS_DOMAIN_NAME:-}" "${AWS_JUPYTER_DOMAIN_NAME:-}" "${OIDC_DOMAIN_NAME:-}"); do
  ZONE_ID="$(aws route53 list-hosted-zones-by-name --dns-name $DOMAIN --max-items 1 \
               --query "HostedZones[?Name=='$DOMAIN.'].Id | [0]" --output text 2>/dev/null || true)"
  NAMESERVERS=""
  if [ -n "$ZONE_ID" ] && [ "$ZONE_ID" != "None" ]; then
    NAMESERVERS="$(aws route53 get-hosted-zone --id $ZONE_ID --query 'DelegationSet.NameServers' --output text 2>/dev/null || true)"
  fi
  show_dns_zone AWS "$DOMAIN" "$NAMESERVERS"
done
