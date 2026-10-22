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

# Prints the names given, one per line, lower case and without the trailing dot, sorted
dns_normalise() {
  tr '[:space:];' '\n\n' <<<"$*" | tr '[:upper:]' '[:lower:]' | sed 's/\.$//' | grep -v '^$' | sort
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
  local cloud="$1" domain="$2" ours="$3" ours_sorted public ns
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
    echo "    NOT delegated: $DNS_RESOLVER has no name servers for it. Set the ones above at the registrar"
  else
    echo "    NOT delegated to this zone: $DNS_RESOLVER has$(dns_provider_of "$public")"
    for ns in $public; do
      echo "      $ns"
    done
    echo "    Set the ones above at the registrar, see docs/dns_config.md#domain-name-registration"
  fi
}
