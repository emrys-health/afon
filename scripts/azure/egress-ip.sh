source $AFON_ROOT/scripts/config/afon-source.sh azure

DEFAULT_IP_NAME="afon-egress-ip"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|show> [<name>]"
  echo "Creates, or shows, the public IP in AZURE_RESOURCE_GROUP that the outbound traffic of the cluster egresses from, when"
  echo "AZURE_EGRESS_IP is set to its address, see docs/cluster.md#egress-ip-azure.  It is a Standard SKU, static public IP,"
  echo "in AZURE_REGION, which the stack looks up rather than manages, so it is not deleted with the stack, and not by this script."
  echo "<name> is that of the public IP (default: $DEFAULT_IP_NAME)."
}

ACTION="${1:-}"
IP_NAME="${2:-$DEFAULT_IP_NAME}"

for PROPERTY in AZURE_RESOURCE_GROUP AZURE_REGION; do
  if [ -z "${!PROPERTY:-}" ]; then
    echo "- $PROPERTY is not set - set it in afon-azure.properties"
    exit 1
  fi
done

# Whether the public IP exists
ip_exists() {
  az network public-ip show --name $IP_NAME --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null
}

# Prints the address of the public IP, warns if it cannot serve as the egress IP, and tells how to configure it
show_address() {
  IP_ADDRESS="$(az network public-ip show --name $IP_NAME --resource-group $AZURE_RESOURCE_GROUP --query ipAddress -o tsv)"
  IP_SKU="$(az network public-ip show --name $IP_NAME --resource-group $AZURE_RESOURCE_GROUP --query sku.name -o tsv)"
  IP_ALLOCATION="$(az network public-ip show --name $IP_NAME --resource-group $AZURE_RESOURCE_GROUP --query publicIPAllocationMethod -o tsv)"
  echo "- Public IP $IP_NAME in $AZURE_RESOURCE_GROUP: $IP_ADDRESS ($IP_SKU, $IP_ALLOCATION)"
  if [ "$IP_SKU" != "Standard" ] || [ "$IP_ALLOCATION" != "Static" ]; then
    echo "- WARNING: the cluster's load balancer can only use a Standard SKU, static public IP as its outbound IP"
  fi
  if [ "${AZURE_EGRESS_IP:-}" = "$IP_ADDRESS" ]; then
    echo "- AZURE_EGRESS_IP is already set to it"
  else
    echo "- Set it in afon-azure.properties:"
    echo "AZURE_EGRESS_IP=$IP_ADDRESS"
  fi
}

case "$ACTION" in
  create)
    if ip_exists; then
      echo "- Public IP $IP_NAME already exists in $AZURE_RESOURCE_GROUP, skipping"
    else
      echo "- Creating public IP $IP_NAME in $AZURE_RESOURCE_GROUP, in $AZURE_REGION"
      az network public-ip create --name $IP_NAME --resource-group $AZURE_RESOURCE_GROUP --location $AZURE_REGION \
        --sku Standard --allocation-method Static -o none || exit 1
    fi
    show_address
    ;;

  show)
    if ip_exists; then
      show_address
    else
      echo "- Public IP $IP_NAME does not exist in $AZURE_RESOURCE_GROUP"
    fi
    ;;

  *)
    usage
    exit 1
    ;;
esac
