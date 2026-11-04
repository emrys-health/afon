#!/usr/bin/env bash
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
  echo "to repeat."
  echo "A domain name with no such parent zone that is itself registered with Route 53 in the current AWS account has its registered"
  echo "nameservers set to those of the zone instead, REPLACING the ones it has, which are shown first: its DNS is then served by the zone"
  echo "of the cloud, so anything else in the domain's current zone stops resolving.  Nothing is changed if they are the same already."
  echo "Any other domain name is left for you to delegate by hand."
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

# Sets the nameservers registered for the domain name with Route 53 Domains to those given, if it is registered in the current AWS
# account and they differ, saying what it replaces.  Route 53 Domains is only served from us-east-1.  Returns 2 if the domain name is
# not registered in this account (or its registration cannot be read), 1 if the update fails.
write_route53_registration() {
  local domain="$1" nameservers="$2" current wanted ns out args=()
  if ! current="$(aws route53domains get-domain-detail --region us-east-1 --domain-name "$domain" \
                    --query 'Nameservers[].Name' --output text 2>/dev/null)"; then
    return 2
  fi
  current="$(dns_normalise "$current")"
  wanted="$(dns_normalise "$nameservers")"
  if [ "$current" = "$wanted" ]; then
    echo "    $domain is already registered with those nameservers, nothing to change"
    return 0
  fi
  echo "    $domain is registered with Route 53 Domains: REPLACING its registered nameservers"
  echo "      current: $(echo $current)"
  echo "      new:     $(echo $wanted)"
  for ns in $nameservers; do
    args+=("Name=$ns")
  done
  if ! out="$(aws route53domains update-domain-nameservers --region us-east-1 --domain-name "$domain" --nameservers "${args[@]}" \
                --query 'OperationId' --output text 2>&1)"; then
    # the registration shows the old nameservers until the update is applied, so a repeat finds it still pending
    if [[ "$out" == *DuplicateRequest* ]]; then
      echo "    an update of the nameservers of $domain is already pending: wait for it to finish (Route 53 -> Domains -> Requests), then repeat"
      return 0
    fi
    echo "$out"
    return 1
  fi
  echo "    submitted: the registry takes a while to apply it (the operation is under Route 53 -> Domains -> Requests)"
}

# Upserts the NS record of the domain name, with the name servers given, in the Route 53 zone of its parent domain, or, if it has no
# such parent and is registered with Route 53 in the current AWS account, sets its registered nameservers
write_route53_delegation() {
  local domain="$1" nameservers="$2" parent parent_id parent_name values="" ns status
  if ! parent="$(parent_zone $domain)"; then
    write_route53_registration $domain "$nameservers" && return 0 || status=$?
    if [ $status -eq 2 ]; then
      echo "    no Route 53 hosted zone for a parent domain of $domain, and it is not registered with Route 53 in this account (or its registration cannot be read), so add the records"
      echo "    above to its parent zone, or set the nameservers at the registrar"
      return 0
    fi
    return 1
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
