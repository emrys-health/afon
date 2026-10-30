# Shared by aws/dns-zones.sh, azure/dns-zones.sh and gcp/dns-zones.sh, which source it, to show the nameservers of the DNS zone of
# each domain name of the cloud, and whether the registrar delegates the domain to them, so that the three do it the same way.  Not
# run on its own.
#
# The domain names are those that the cloud's stacks serve: the Polaris and Jupyter domain names of the cloud, and OIDC_DOMAIN_NAME,
# which the cloud serves if it deploys the KeyCloak.  The delegation is what the public DNS says, asked of DNS_RESOLVER (default
# 8.8.8.8), and so can still be the old one for a while after the registrar changes it.

DNS_RESOLVER="${DNS_RESOLVER:-8.8.8.8}"

# Prints the names given, those that are set, once each
dns_unique_domains() {
  local seen=" " name
  for name in "$@"; do
    if [ -z "$name" ]; then
      continue
    fi
    case "$seen" in
      *" $name "*) ;;
      *) seen="$seen$name "; echo "$name" ;;
    esac
  done
}

# Prints the domain names that the cloud serves, once each: its Polaris and Jupyter domain names and OIDC_DOMAIN_NAME, which
# afon-source.sh has resolved for the cloud.  Needs the config of the cloud to have been sourced.
dns_cloud_domains() {
  case "$1" in
    aws)   dns_unique_domains "${AWS_POLARIS_DOMAIN_NAME:-}" "${AWS_JUPYTER_DOMAIN_NAME:-}" "${OIDC_DOMAIN_NAME:-}" ;;
    azure) dns_unique_domains "${AZURE_POLARIS_DOMAIN_NAME:-}" "${AZURE_JUPYTER_DOMAIN_NAME:-}" "${OIDC_DOMAIN_NAME:-}" ;;
    gcp)   dns_unique_domains "${GCP_POLARIS_DOMAIN_NAME:-}" "${GCP_JUPYTER_DOMAIN_NAME:-}" "${OIDC_DOMAIN_NAME:-}" ;;
  esac
}

# Fails, saying why, if the config of the cloud lacks what its DNS zones are looked up with
dns_check_cloud_config() {
  case "$1" in
    azure)
      if [ -z "${AZURE_RESOURCE_GROUP:-}" ]; then
        echo "- AZURE_RESOURCE_GROUP is not set - set it in afon-azure.properties"
        return 1
      fi ;;
    gcp)
      if [ -z "${GCP_PROJECT_ID:-}" ]; then
        echo "- GCP_PROJECT_ID is not set - set it in afon-gcp.properties"
        return 1
      fi ;;
  esac
}

# Prints the id of the Route 53 hosted zone named after the domain name, if there is one.  Route 53 is global, so it needs no region.
dns_aws_zone_id() {
  local id
  id="$(aws route53 list-hosted-zones-by-name --dns-name "$1" --max-items 1 \
          --query "HostedZones[?Name=='$1.'].Id | [0]" --output text 2>/dev/null || true)"
  if [ -n "$id" ] && [ "$id" != "None" ]; then
    echo "$id"
  fi
}

# Prints the name servers of the zone named after the domain name in the cloud, separated by whitespace, or nothing if there is none
dns_zone_nameservers() {
  local cloud="$1" domain="$2" zone_id
  case "$cloud" in
    aws)
      zone_id="$(dns_aws_zone_id "$domain")"
      if [ -n "$zone_id" ]; then
        aws route53 get-hosted-zone --id "$zone_id" --query 'DelegationSet.NameServers' --output text 2>/dev/null || true
      fi ;;
    azure)
      az network dns zone show --name "$domain" --resource-group "$AZURE_RESOURCE_GROUP" --query 'nameServers' --output tsv \
        2>/dev/null || true ;;
    gcp)
      gcloud dns managed-zones describe "${domain//./-}" --project "$GCP_PROJECT_ID" --format='value(nameServers)' \
        2>/dev/null || true ;;
  esac
}

# dns_set_stack_domains <cloud> <stack id|role>: sets DNS_DOMAINS to the domain names that the stack serves from the cluster, once
# each, as stacks.yaml and the config decide, see scripts/stack/needs-source.sh, and DNS_STACK_ID to its id.  Not in a subshell.
# Fails, saying why, if the stack is not found or a domain name it needs is not set.
dns_set_stack_domains() {
  local cloud="$1" stack="$2" property domain
  source $AFON_ROOT/scripts/stack/needs-source.sh
  stack_needs "$cloud" "$stack" || return 1
  DNS_STACK_ID="$AFON_STACK_ID"
  DNS_DOMAINS=""
  for property in $AFON_STACK_DOMAINS; do
    domain="${!property:-}"
    if [ -z "$domain" ]; then
      echo "-- $property is not set, which stack '$DNS_STACK_ID' needs for its DNS zone"
      return 1
    fi
    DNS_DOMAINS="$DNS_DOMAINS $domain"
  done
  DNS_DOMAINS="$(dns_unique_domains $DNS_DOMAINS | tr '\n' ' ')"
}

# Creates the DNS zone named after the domain name in the cloud: a Route 53 hosted zone, an Azure DNS zone in AZURE_RESOURCE_GROUP,
# which must exist, or a Cloud DNS managed zone in GCP_PROJECT_ID, named after the domain name with its dots replaced by dashes, as
# GcpCloudProvider names it.  Fails, saying why, if it cannot.
dns_create_zone() {
  local cloud="$1" domain="$2" api="dns.googleapis.com"
  case "$cloud" in
    aws)
      aws route53 create-hosted-zone --name "$domain" --caller-reference "afon-$domain-$(date +%s)" \
        --query 'ChangeInfo.Id' --output text >/dev/null ;;
    azure)
      az network dns zone create --resource-group "$AZURE_RESOURCE_GROUP" --name "$domain" >/dev/null ;;
    gcp)
      if ! gcloud services list --enabled --project="$GCP_PROJECT_ID" --filter="config.name=$api" --format="value(config.name)" \
             2>/dev/null | grep -q "^$api$"; then
        echo "- Enabling API $api"
        gcloud services enable $api --project="$GCP_PROJECT_ID" || return 1
      fi
      gcloud dns managed-zones create "${domain//./-}" --dns-name="$domain." --description="Afon DNS zone of $domain" \
        --project="$GCP_PROJECT_ID" >/dev/null ;;
  esac
}

# Prints the names given, one per line, lower case and without the trailing dot, sorted
dns_normalise() {
  tr '[:space:];' '\n\n' <<<"$*" | tr '[:upper:]' '[:lower:]' | sed 's/\.$//' | { grep -v '^$' || true; } | sort
}

# Prints, after a space, which provider the name servers given belong to, if it is known, eg " (AWS Route 53)", else nothing
dns_provider_of() {
  case "$1" in
    *awsdns*) echo " (AWS Route 53)" ;;
    *azure-dns*) echo " (Azure DNS)" ;;
    *googledomains*|*ns-cloud-*) echo " (Google Cloud DNS)" ;;
  esac
}

# show_dns_zone <cloud> <domain name> <nameservers of its zone in the cloud, separated by whitespace or semicolons, or empty if the cloud
# has no such zone>: shows them, and whether the domain is delegated to them
show_dns_zone() {
  local cloud="$1" domain="$2" ours="$3" ours_sorted public ns cmd
  # the cloud as the commands know it, eg dns-delegate azure, and as it is shown
  cmd="$(echo "$cloud" | tr '[:upper:]' '[:lower:]')"
  case "$cmd" in
    aws) cloud="AWS" ;;
    azure) cloud="Azure" ;;
    gcp) cloud="GCP" ;;
  esac
  local fix="Set the ones above at the registrar, or for a subdomain run: afon-cli.sh dns-delegate $cmd <stack> [--route53]"
  if [ -z "$ours" ]; then
    echo "- $domain: no DNS zone in $cloud"
    return 0
  fi
  ours_sorted="$(dns_normalise "$ours")"
  echo "- $domain, zone in $cloud:"
  for ns in $ours_sorted; do
    echo "    $ns"
  done
  if ! command -v dig >/dev/null 2>&1; then
    echo "    (dig not found, so not checking the delegation)"
    return 0
  fi
  public="$(dns_normalise "$(dig @"$DNS_RESOLVER" NS "$domain" +short 2>/dev/null)")"
  if [ "$public" = "$ours_sorted" ]; then
    echo "    delegated to this zone, as $DNS_RESOLVER sees it"
  elif [ -z "$public" ]; then
    echo "    NOT delegated: $DNS_RESOLVER has no name servers for it. $fix"
  else
    echo "    NOT delegated to this zone: $DNS_RESOLVER has$(dns_provider_of "$public")"
    for ns in $public; do
      echo "      $ns"
    done
    echo "    $fix, see docs/dns_config.md#domain-name-registration"
  fi
}
