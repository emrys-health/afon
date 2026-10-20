source $AFON_ROOT/scripts/config/afon-source.sh gcp

DEFAULT_IP_NAME="afon-egress-ip"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|show> [<name>]"
  echo "Creates, or shows, the static IP in GCP_PROJECT_ID that the outbound traffic of the cluster egresses from, through its Cloud"
  echo "NAT, when GCP_EGRESS_IP is set to its address, see docs/cluster.md#egress-ip-gcp.  It is a regional, external static IP, in"
  echo "GCP_REGION, which the stack looks up rather than manages, so it is not deleted with the stack, and not by this script."
  echo "<name> is that of the static IP (default: $DEFAULT_IP_NAME)."
}

ACTION="${1:-}"
IP_NAME="${2:-$DEFAULT_IP_NAME}"

for PROPERTY in GCP_PROJECT_ID GCP_REGION; do
  if [ -z "${!PROPERTY:-}" ]; then
    echo "- $PROPERTY is not set - set it in afon-gcp.properties"
    exit 1
  fi
done

# Prints the field $1 of the static IP, as gcloud formats it, eg address
ip_field() {
  gcloud compute addresses describe $IP_NAME --region $GCP_REGION --project $GCP_PROJECT_ID --format="value($1)"
}

# Whether the static IP exists
ip_exists() {
  gcloud compute addresses describe $IP_NAME --region $GCP_REGION --project $GCP_PROJECT_ID --format="value(address)" >/dev/null 2>&1
}

# Prints the address of the static IP, warns if it cannot serve as the egress IP, and tells how to configure it
show_address() {
  IP_ADDRESS="$(ip_field address)"
  IP_TYPE="$(ip_field addressType)"
  IP_STATUS="$(ip_field status)"
  echo "- Static IP $IP_NAME in $GCP_PROJECT_ID, $GCP_REGION: $IP_ADDRESS ($IP_TYPE, $IP_STATUS)"
  if [ "$IP_TYPE" != "EXTERNAL" ]; then
    echo "- WARNING: the Cloud NAT can only use a regional, external static IP"
  fi
  if [ "${GCP_EGRESS_IP:-}" = "$IP_ADDRESS" ]; then
    echo "- GCP_EGRESS_IP is already set to it"
  else
    echo "- Set it in afon-gcp.properties:"
    echo "GCP_EGRESS_IP=$IP_ADDRESS"
  fi
}

case "$ACTION" in
  create)
    if ip_exists; then
      echo "- Static IP $IP_NAME already exists in $GCP_PROJECT_ID, $GCP_REGION, skipping"
    else
      echo "- Creating static IP $IP_NAME in $GCP_PROJECT_ID, $GCP_REGION"
      gcloud compute addresses create $IP_NAME --region $GCP_REGION --project $GCP_PROJECT_ID || exit 1
    fi
    show_address
    ;;

  show)
    if ip_exists; then
      show_address
    else
      echo "- Static IP $IP_NAME does not exist in $GCP_PROJECT_ID, $GCP_REGION"
    fi
    ;;

  *)
    usage
    exit 1
    ;;
esac
