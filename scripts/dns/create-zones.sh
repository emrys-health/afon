usage(){
  echo "Usage: ${BASH_SOURCE[0]} aws|azure|gcp <stack-id|identity|catalog|tre>"
  echo "Creates the DNS zone of each domain name that the stack serves from the cluster, ie those of its components, as stacks.yaml and the"
  echo "config decide, eg AWS_POLARIS_DOMAIN_NAME, AWS_JUPYTER_DOMAIN_NAME and OIDC_DOMAIN_NAME.  The zone is named after the domain name,"
  echo "which can be a registered domain or a subdomain of one, eg aws-polaris.emrys.click, see docs/dns_config.md.  A zone that already"
  echo "exists is left as it is, so that this is safe to repeat.  It shows the nameservers of each zone."
  echo ""
  echo "The zones are a Route 53 hosted zone on aws, an Azure DNS zone in AZURE_RESOURCE_GROUP on azure, which <cloud>-create creates"
  echo "and so must have been run, and a Cloud DNS managed zone in GCP_PROJECT_ID on gcp."
  echo "Once the zones exist, run dns-delegate to delegate them from the parent domain."
}

CLOUD=""
STACK=""
for ARG in "$@"; do
  case "$ARG" in
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

FAILED=false
for DOMAIN in $DNS_DOMAINS; do
  if [ -n "$(dns_zone_nameservers $CLOUD $DOMAIN)" ]; then
    echo "- $DOMAIN: DNS zone already exists"
  else
    echo "- $DOMAIN: creating the DNS zone"
    if ! dns_create_zone $CLOUD $DOMAIN; then
      echo "- $DOMAIN: could not create the DNS zone"
      FAILED=true
      continue
    fi
  fi
  show_dns_zone $CLOUD "$DOMAIN" "$(dns_zone_nameservers $CLOUD $DOMAIN)"
done

if $FAILED; then
  exit 1
fi
echo ""
echo "Next, delegate the domain names from their parent domain: afon-cli.sh dns-delegate $CLOUD $STACK [--route53]"
echo "(or set the nameservers above at the registrar for a registered domain), then check with afon-cli.sh ${CLOUD}-dns-zones"
