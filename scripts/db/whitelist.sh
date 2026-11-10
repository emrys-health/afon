#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh ""
source $AFON_ROOT/scripts/config/secret-source.sh ALL

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [--dry-run] [<aws|azure|gcp|all> ...]"
  echo "Allows the egress IP of each cloud given (default and "all": all three), ie its <CLOUD>_EGRESS_IP, to connect to the Postgres instance of"
  echo "POLARIS_DB_HOST on its port, by adding <CLOUD>_EGRESS_IP/32 to the firewall of the instance.  A cloud with no <CLOUD>_EGRESS_IP is"
  echo "skipped, as its egress IP is not pinned.  An IP that is allowed already is left as it is.  With --dry-run, only says what it would add."
  echo "The cloud that hosts the instance is POLARIS_DB_CLOUD, set in this script (${POLARIS_DB_CLOUD}) until it is configurable."
  echo "The providers supported are: ${PROVIDERS[*]}."
}

# The cloud that hosts the database, which is not configured yet: set it here to another of the providers below if it moves
POLARIS_DB_CLOUD=aws

# The providers that host the database, ie those that have the functions provider_<provider>_find, _allowed and _allow below
PROVIDERS=(aws)

DRY_RUN=false
CLOUDS=()
for ARG in "$@"; do
  case "$ARG" in
    --dry-run) DRY_RUN=true ;;
    --help|-h) usage; exit 0 ;;
    aws|azure|gcp) CLOUDS+=("$ARG") ;;
    all) CLOUDS+=(aws azure gcp) ;;
    *) echo "- Unknown argument '$ARG'"; usage; exit 1 ;;
  esac
done
[ ${#CLOUDS[@]} -eq 0 ] && CLOUDS=(aws azure gcp)
# the clouds once each, as "all" may come with one of them
CLOUDS=($(printf "%s\n" "${CLOUDS[@]}" | awk '!seen[$0]++'))

if [ -z "${POLARIS_DB_HOST:-}" ]; then
  echo "- POLARIS_DB_HOST is not set"
  exit 1
fi

# Prints the value of the property $2 as the config of the cloud $1 has it, if it is set: read in a subshell, so that the clouds do not mix
property_of() {
  (
    set +eu
    source $AFON_ROOT/scripts/config/afon-source.sh "$1" > /dev/null 2>&1
    source $AFON_ROOT/scripts/config/secret-source.sh "$1" > /dev/null 2>&1
    printf '%s' "${!2:-}"
  )
}

# --- AWS: an RDS instance, whose security group has the rules ---------------------------------------------------------------------------
# Each provider has three functions, which share what they find in the variables DB_PORT and FIREWALL:
#   provider_<p>_find:        sets DB_PORT and FIREWALL (what holds the rules, for the messages), or exits with a message
#   provider_<p>_allowed:     prints the CIDRs that are allowed to connect, separated by spaces
#   provider_<p>_allow <cidr> <description>: allows the CIDR to connect
provider_aws_find() {
  AWS_REGION="$(property_of aws AWS_REGION)"
  if [ -z "$AWS_REGION" ]; then
    echo "- AWS_REGION is not set"
    exit 1
  fi
  local INSTANCE DB_ID GROUP_COUNT
  INSTANCE="$(aws rds describe-db-instances --region $AWS_REGION \
    --query "DBInstances[?Endpoint.Address=='$POLARIS_DB_HOST']|[0].[DBInstanceIdentifier,Endpoint.Port,VpcSecurityGroups[0].VpcSecurityGroupId,length(VpcSecurityGroups)]" \
    --output text)" || exit 1
  read -r DB_ID DB_PORT FIREWALL GROUP_COUNT <<< "$INSTANCE"
  if [ -z "${DB_ID:-}" ] || [ "$DB_ID" == "None" ]; then
    echo "- No RDS instance in $AWS_REGION has the endpoint POLARIS_DB_HOST, or the AWS account is not the one of the instance"
    exit 1
  fi
  if [ "$GROUP_COUNT" != "1" ]; then
    echo "- The instance '$DB_ID' has $GROUP_COUNT security groups, not one: add the rules by hand"
    exit 1
  fi
  echo "- RDS instance '$DB_ID', port $DB_PORT, security group '$FIREWALL'"
}

provider_aws_allowed() {
  aws ec2 describe-security-groups --region $AWS_REGION --group-ids $FIREWALL \
    --query "SecurityGroups[0].IpPermissions[?IpProtocol=='tcp' && FromPort<=\`$DB_PORT\` && ToPort>=\`$DB_PORT\`].IpRanges[].CidrIp" \
    --output text | tr '\t' ' '
}

provider_aws_allow() {
  aws ec2 authorize-security-group-ingress --region $AWS_REGION --group-id $FIREWALL \
    --ip-permissions "IpProtocol=tcp,FromPort=$DB_PORT,ToPort=$DB_PORT,IpRanges=[{CidrIp=$1,Description=$2}]" \
    --output text > /dev/null
}

# --- main ---------------------------------------------------------------------------------------------------------------------------------
PROVIDER="$POLARIS_DB_CLOUD"
if [[ ! " ${PROVIDERS[*]} " == *" $PROVIDER "* ]]; then
  echo "- The database is hosted by '$PROVIDER', which this script does not support (supported: ${PROVIDERS[*]})."
  echo "- Add the egress IPs to its firewall by hand."
  exit 1
fi
DB_PORT=""
FIREWALL=""
provider_${PROVIDER}_find
ALLOWED="$(provider_${PROVIDER}_allowed)" || exit 1

for CLOUD in "${CLOUDS[@]}"; do
  EGRESS_PROPERTY="$(echo $CLOUD | tr '[:lower:]' '[:upper:]')_EGRESS_IP"
  EGRESS_IP="$(property_of $CLOUD $EGRESS_PROPERTY)"
  EGRESS_IP="${EGRESS_IP//[[:space:]]/}"
  if [ -z "$EGRESS_IP" ]; then
    echo "- $EGRESS_PROPERTY is not set, skipping $CLOUD"
    continue
  fi
  CIDR="$EGRESS_IP/32"
  if [[ " $ALLOWED " == *" $CIDR "* ]]; then
    echo "- $CIDR ($CLOUD) is allowed already, skipping"
  elif [ "$DRY_RUN" == "true" ]; then
    echo "- Would allow $CIDR ($CLOUD) on port $DB_PORT of '$FIREWALL'"
  else
    echo "- Allowing $CIDR ($CLOUD) on port $DB_PORT of '$FIREWALL'"
    provider_${PROVIDER}_allow "$CIDR" "afon ${ENV_NAME:-} $CLOUD egress" || exit 1
  fi
done
