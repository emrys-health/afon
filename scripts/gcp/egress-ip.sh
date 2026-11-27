#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh gcp

DEFAULT_IP_NAME="afon-egress-ip"
# For a project stack, run with 'manage-project-stack run <project-stack> ...', the address of its own, named after it, as one address serves the outbound traffic of
# one cluster, so that project stacks that run next to each other each need their own
if [ -n "${AFON_PROJECT_STACK:-}" ]; then
  DEFAULT_IP_NAME="afon-egress-ip-$AFON_PROJECT_STACK"
fi

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|show|delete> [<name>]"
  echo "Creates, or shows, the static IP in GCP_PROJECT_ID that the outbound traffic of the cluster egresses from, through its Cloud"
  echo "NAT, when GCP_EGRESS_IP is set to its address, see docs/cluster.md#egress-ip-gcp.  It is a regional, external static IP, in"
  echo "GCP_REGION, which the stack looks up rather than manages, so it is not deleted with the stack: only delete deletes it, and never while it is in use, ie while a stack uses it."
  echo "<name> is that of the static IP (default: afon-egress-ip, or, run for a project stack, afon-egress-ip-<project-stack>: $DEFAULT_IP_NAME)."
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
  if [ -n "${AFON_PROJECT_STACK:-}" ]; then
    # a project stack carries its own address, not the env's
    echo "- Set it as egressIp in the projectStack section of the file of the project stack $AFON_PROJECT_STACK, ~/.afon/projects/$AFON_PROJECT_STACK/project.yaml:"
    echo "egressIp: $IP_ADDRESS"
  elif [ "${GCP_EGRESS_IP:-}" = "$IP_ADDRESS" ]; then
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

  delete)
    if ! ip_exists; then
      echo "- Static IP $IP_NAME does not exist in $GCP_PROJECT_ID, $GCP_REGION, nothing to delete"
      exit 0
    fi
    IP_ADDRESS="$(ip_field address)"
    IP_STATUS="$(ip_field status)"
    if [ "$IP_STATUS" != "RESERVED" ]; then
      echo "- Static IP $IP_NAME ($IP_ADDRESS) is $IP_STATUS, not RESERVED, so a stack uses it: delete that stack first, nothing is deleted"
      exit 1
    fi
    echo "- Deleting static IP $IP_NAME ($IP_ADDRESS) in $GCP_PROJECT_ID, $GCP_REGION"
    gcloud compute addresses delete $IP_NAME --region $GCP_REGION --project $GCP_PROJECT_ID --quiet || exit 1
    echo "- Deleted $IP_ADDRESS: anything that allowed it, eg a database or a Data Provider, no longer has this address to allow"
    ;;

  *)
    usage
    exit 1
    ;;
esac
