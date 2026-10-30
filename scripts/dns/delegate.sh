usage(){
  echo "Usage: ${BASH_SOURCE[0]} aws|azure|gcp <stack-id|identity|catalog|tre> [--route53]"
  echo "Shows the NS records that delegate each domain name that the stack serves, see dns-create-zones,"
  echo "from the DNS zone of its parent domain to the zone of the cloud, as zone file lines that can be added to the parent zone wherever"
  echo "it is hosted, eg for aws-polaris.emrys.click:"
  echo "  aws-polaris.emrys.click. 300 IN NS ns-123.awsdns-45.com."
  echo "For a registered domain, rather than a subdomain, the nameservers are set at the registrar instead, see docs/dns_config.md."
  echo ""
  echo "With --route53, the records are also written to the Route 53 hosted zone of the parent domain, ie the longest suffix of the domain"
  echo "name that is a public hosted zone of the current AWS account, replacing any existing NS record of the domain name, so that it is safe"
  echo "to repeat.  A domain name with no such parent zone is left for you to delegate by hand."
}

CLOUD=""
STACK=""
ROUTE53=false
for ARG in "$@"; do
  case "$ARG" in
    --route53)     ROUTE53=true ;;
    aws|azure|gcp) CLOUD="$ARG" ;;
    -*)            usage; exit 1 ;;
    *)             STACK="$ARG" ;;
  esac
done
if [ -z "$CLOUD" ] || [ -z "$STACK" ]; then
  usage
  exit 1
fi

source $AFON_ROOT/scripts/config/afon-source.sh $CLOUD
source $AFON_ROOT/scripts/dns/zones-source.sh

dns_check_cloud_config $CLOUD || exit 1
dns_set_stack_domains $CLOUD $STACK || exit 1
if $ROUTE53 && ! command -v aws >/dev/null 2>&1; then
  echo "- aws not found, which --route53 needs to write to Route 53"
  exit 1
fi

# Prints the id and name of the public Route 53 hosted zone of the closest parent domain of the domain name that has one, if any.
# A name with no dot left is a top level domain, which is not a parent worth looking for.
parent_zone() {
  local name="${1#*.}" id
  while [[ "$name" == *.* ]]; do
    id="$(aws route53 list-hosted-zones-by-name --dns-name "$name" --max-items 1 \
            --query "HostedZones[?Name=='$name.' && Config.PrivateZone==\`false\`].Id | [0]" --output text 2>/dev/null || true)"
    if [ -n "$id" ] && [ "$id" != "None" ]; then
      echo "$id $name"
      return 0
    fi
    name="${name#*.}"
  done
  return 1
}

# Upserts the NS record of the domain name, with the name servers given, in the Route 53 zone of its parent domain
write_route53_delegation() {
  local domain="$1" nameservers="$2" parent parent_id parent_name values="" ns
  if ! parent="$(parent_zone $domain)"; then
    echo "    no Route 53 hosted zone for a parent domain of $domain, so add the records above to its parent zone, or at the registrar"
    return 0
  fi
  parent_id="${parent% *}"
  parent_name="${parent#* }"
  for ns in $nameservers; do
    values="$values{\"Value\":\"$ns.\"},"
  done
  echo "    writing them to the Route 53 zone of $parent_name ($parent_id)"
  aws route53 change-resource-record-sets --hosted-zone-id "$parent_id" --change-batch \
    "{\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{\"Name\":\"$domain.\",\"Type\":\"NS\",\"TTL\":300,\"ResourceRecords\":[${values%,}]}}]}" \
    --query 'ChangeInfo.Id' --output text >/dev/null
}

FAILED=false
for DOMAIN in $DNS_DOMAINS; do
  NAMESERVERS="$(dns_normalise "$(dns_zone_nameservers $CLOUD $DOMAIN)")"
  if [ -z "$NAMESERVERS" ]; then
    echo "- $DOMAIN: no DNS zone in $CLOUD - create it first, see docs/dns_config.md"
    FAILED=true
    continue
  fi
  echo "- $DOMAIN, NS records for the zone of its parent domain:"
  for NS in $NAMESERVERS; do
    echo "    $DOMAIN. 300 IN NS $NS."
  done
  if $ROUTE53 && ! write_route53_delegation $DOMAIN "$NAMESERVERS"; then
    echo "    could not write them"
    FAILED=true
  fi
done

if ! $ROUTE53; then
  echo ""
  echo "Add the records to the zone of the parent domain, or run with --route53 if that is a Route 53 hosted zone of this AWS account."
  echo "Then check the delegation with ${CLOUD}-dns-zones."
fi

if $FAILED; then
  exit 1
fi
