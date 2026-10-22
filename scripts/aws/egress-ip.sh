source $AFON_ROOT/scripts/config/afon-source.sh aws

DEFAULT_IP_NAME="afon-egress-ip"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|show> [<name>]"
  echo "Creates, or shows, the Elastic IP in AWS_REGION that the outbound traffic of the cluster egresses from, through its NAT"
  echo "gateway, when AWS_EGRESS_IP is set to its address, see docs/cluster.md#egress-ip-aws.  It is a VPC Elastic IP, found by its"
  echo "Name tag, which the stack looks up rather than manages, so it is not released with the stack, and not by this script."
  echo "<name> is that of the Elastic IP (default: $DEFAULT_IP_NAME)."
}

ACTION="${1:-}"
IP_NAME="${2:-$DEFAULT_IP_NAME}"

if [ -z "${AWS_REGION:-}" ]; then
  echo "- AWS_REGION is not set - set it in afon-aws.properties"
  exit 1
fi

# Prints the field $1 of the Elastic IP named by its Name tag, as the AWS CLI queries it, eg PublicIp, or nothing if there is none
ip_field() {
  local value
  value="$(aws ec2 describe-addresses --region $AWS_REGION --filters "Name=tag:Name,Values=$IP_NAME" \
             --query "Addresses[0].$1" --output text 2>/dev/null)" || return 0
  if [ "$value" != "None" ]; then
    echo "$value"
  fi
}

# Whether the Elastic IP exists
ip_exists() {
  [ -n "$(ip_field PublicIp)" ]
}

# Prints the address of the Elastic IP, warns if it cannot serve as the egress IP, and tells how to configure it
show_address() {
  IP_ADDRESS="$(ip_field PublicIp)"
  IP_DOMAIN="$(ip_field Domain)"
  IP_ASSOCIATION="$(ip_field AssociationId)"
  echo "- Elastic IP $IP_NAME in $AWS_REGION: $IP_ADDRESS (${IP_DOMAIN:-unknown domain}, ${IP_ASSOCIATION:+associated}${IP_ASSOCIATION:-unassociated})"
  if [ "$IP_DOMAIN" != "vpc" ]; then
    echo "- WARNING: the NAT gateway can only use a VPC Elastic IP"
  fi
  if [ -n "$IP_ASSOCIATION" ]; then
    echo "- WARNING: it is already associated, so it may be in use by another stack: one Elastic IP can serve one NAT gateway"
  fi
  if [ "${AWS_EGRESS_IP:-}" = "$IP_ADDRESS" ]; then
    echo "- AWS_EGRESS_IP is already set to it"
  else
    echo "- Set it in afon-aws.properties:"
    echo "AWS_EGRESS_IP=$IP_ADDRESS"
  fi
}

case "$ACTION" in
  create)
    if ip_exists; then
      echo "- Elastic IP $IP_NAME already exists in $AWS_REGION, skipping"
    else
      echo "- Allocating Elastic IP $IP_NAME in $AWS_REGION"
      aws ec2 allocate-address --region $AWS_REGION --domain vpc \
        --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$IP_NAME}]" --output text >/dev/null || exit 1
    fi
    show_address
    ;;

  show)
    if ip_exists; then
      show_address
    else
      echo "- Elastic IP $IP_NAME does not exist in $AWS_REGION"
    fi
    ;;

  *)
    usage
    exit 1
    ;;
esac
