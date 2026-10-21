#!/usr/bin/env bash
# Shows, sets or removes the restriction of who can call the Polaris ingress of a cluster, live, ie by the annotation of its ingress,
# which the next deploy, or `tofu-apply`, sets again as the config says.  It is how to let the machine running `setup` call Polaris
# of a stack deployed with AWS_, AZURE_ or GCP_POLARIS_ALLOWED_CIDRS, and how to restrict it again for the `network`
# smoke test, which needs the machine to be stopped:
#   - the annotation is alb.ingress.kubernetes.io/inbound-cidrs on AWS, and nginx.ingress.kubernetes.io/whitelist-source-range on
#     Azure, which only works with externalTrafficPolicy Local on the ingress controller's service, so `set` sets that too;
#   - what is set is the CIDRs given, else the configured AWS_, AZURE_ or GCP_POLARIS_ALLOWED_CIDRS, plus the
#     cluster's own addresses, as the deploy adds them, so that its pods calling Polaris by its public domain name are not locked out:
#     its egress IP on AWS, or its outbound IP and pod CIDR on Azure;
#   - with --caller, the address of this machine is added to those, which is how to let it call Polaris, eg to run the Polaris steps of
#     `setup`, without taking the restriction off for everybody.  `set` again, without --caller, takes it off.
#
# Usage:
#   ./polaris-whitelist.sh <aws|azure|gcp> show
#   ./polaris-whitelist.sh <aws|azure|gcp> set [--caller] [<cidrs>]
#   ./polaris-whitelist.sh <aws|azure|gcp> remove
#
#   show     prints how the ingress is restricted, if it is, and for Azure the traffic policy of the ingress controller.
#   set      restricts it, to the comma-separated CIDRs given, eg 203.0.113.7/32,198.51.100.0/24, else the configured ones, plus
#            the cluster's own addresses.  With --caller, the IPv4 address of this machine, as https://ifconfig.me sees it, is added
#            to those: to the CIDRs given, else to the configured ones.  `set` again, without --caller, takes it off, as it sets the
#            list from the config, or from the CIDRs given, and not from the annotation as it is.
#            It fails, changing nothing, if the address cannot be learnt.  Mind that it is the address of the route to the internet,
#            so it is not what Polaris sees through a VPN or a proxy that routes to the cluster differently.
#   remove   removes the restriction, so that Polaris can be called from anywhere, until the next deploy.
#
# Env vars:
#   CONTEXT         kubectl context of the cluster (defaults to the current context), whose kind must be that of the cloud.
#   CALLER_IP_URL   the service that says the address it is called from (defaults to https://ifconfig.me).
#
# Examples:
#   afon-cli.sh azure-update-kube-config tre
#   afon-cli.sh kube-polaris-whitelist azure show
#   afon-cli.sh kube-polaris-whitelist azure set --caller  # to run setup from this machine, without taking the restriction off
#   afon-cli.sh kube-polaris-whitelist azure set        # to take this machine off it again, and restrict it for the network smoke test
#   afon-cli.sh kube-polaris-whitelist azure remove     # or to run setup from this machine, with the restriction off
#   afon-cli.sh kube-polaris-whitelist azure set        # to restrict it again, for the network smoke test

set -euo pipefail

AFON_ROOT="${AFON_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

usage() {
  sed -n '/^# Usage:/,/^# Env vars:/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2
}

CLOUD=$(echo "${1:-}" | tr '[:upper:]' '[:lower:]')
ACTION="${2:-}"
case "$CLOUD" in
  aws|azure|gcp) ;;
  *) echo "The cloud should be aws, azure or gcp, not '${1:-}'" >&2; usage; exit 1 ;;
esac
case "$ACTION" in
  show|set|remove) ;;
  *) echo "The action should be show, set or remove, not '$ACTION'" >&2; usage; exit 1 ;;
esac

# what follows the action: for set, the option --caller and the CIDRs to restrict it to, in either order
CALLER=
GIVEN=
HAVE_GIVEN=
shift $(( $# < 2 ? $# : 2 ))
for ARG in "$@"; do
  case "$ARG" in
    --caller) CALLER=true ;;
    -*) echo "Unknown option: $ARG" >&2; usage; exit 1 ;;
    *) [ -z "$HAVE_GIVEN" ] || { echo "Only one list of CIDRs may be given" >&2; usage; exit 1; }
       GIVEN="$ARG"; HAVE_GIVEN=true ;;
  esac
done
if [ "$ACTION" != "set" ] && { [ -n "$CALLER" ] || [ -n "$HAVE_GIVEN" ]; }; then
  echo "$ACTION takes no CIDRs or options" >&2; usage; exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi

source "$AFON_ROOT/scripts/kube/polaris-whitelist-source.sh"

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
ANNOTATION=$(polaris_annotation)

# The cluster is the one of the cloud, as changing the ingress of another one is not what was asked.  The current context is not
# always what it is thought to be, eg after switching env.
case "$CLOUD" in
  aws)
    case "$CONTEXT" in
      arn:aws:eks:*) ;;
      *) echo "The kubectl context '$CONTEXT' is not an EKS cluster: point kubectl at the AWS one, eg with 'afon-cli.sh aws-update-kube-config tre'" >&2
         exit 1 ;;
    esac ;;
  gcp)
    case "$CONTEXT" in
      gke_*) ;;
      *) echo "The kubectl context '$CONTEXT' is not a GKE cluster: point kubectl at the GCP one, eg with 'afon-cli.sh gcp-update-kube-config tre'" >&2
         exit 1 ;;
    esac ;;
  azure)
    if [ -z "$(controller_service "$CONTEXT")" ]; then
      echo "The kubectl context '$CONTEXT' has no nginx ingress controller, as the Azure stacks have: point kubectl at the AKS one," \
           "eg with 'afon-cli.sh azure-update-kube-config tre'" >&2
      exit 1
    fi ;;
esac

STATE=$(ingress_annotation "$ANNOTATION" "$CONTEXT")
if [ "$STATE" = "missing" ]; then
  echo "There is no Polaris ingress (polaris-ingress in the default namespace) in the kubectl context '$CONTEXT'" >&2
  exit 1
fi

CALLER_IP_URL="${CALLER_IP_URL:-https://ifconfig.me}"

# Prints the IPv4 address of this machine, as CALLER_IP_URL sees it, or fails.  Asked for IPv4 only (curl -4): the Polaris domain names
# only have IPv4 addresses, so that is the address a call to Polaris comes from, whereas a dual-stack machine is told its IPv6 one.
caller_ip() {
  local ip
  ip="$(curl -4 -s --max-time 10 "$CALLER_IP_URL" 2>/dev/null | tr -d '[:space:]')" || true
  if ! [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    echo "Could not learn the IPv4 address of this machine from $CALLER_IP_URL (got '${ip:-nothing}'): is it reachable over IPv4?" >&2
    exit 1
  fi
  echo "$ip"
}

# Prints the traffic policy of the ingress controller, on Azure
controller_policy() {
  local controller
  controller=$(controller_service "$CONTEXT")
  kubectl --context "$CONTEXT" get "$controller" -n ingress-nginx -o jsonpath='{.spec.externalTrafficPolicy}'
}

show() {
  echo "- Cluster: $CONTEXT ($CLOUD)"
  case "$STATE" in
    present:?*) echo "- The Polaris ingress is restricted to ${STATE#present:} ($ANNOTATION)" ;;
    present:)   echo "- The Polaris ingress has $ANNOTATION set to nothing" ;;
    *)          echo "- The Polaris ingress is not restricted: $ANNOTATION is not set" ;;
  esac
  if [ "$CLOUD" != "aws" ]; then
    POLICY=$(controller_policy)
    if [ "$POLICY" = "Local" ]; then
      echo "- The traffic policy of its ingress controller is Local, which keeps the callers' IPs"
    else
      echo "- WARNING: the traffic policy of its ingress controller is $POLICY, not Local, so it does not see the callers' IPs and" \
           "its restriction does not work: set restricts it as it should be"
    fi
  fi
}

case "$ACTION" in
  show)
    show
    ;;

  set)
    if [ -n "$HAVE_GIVEN" ]; then
      POLARIS_ALLOWED_CIDRS="$GIVEN"
      for CIDR in ${POLARIS_ALLOWED_CIDRS//,/ }; do
        if ! [[ "$CIDR" =~ ^[0-9a-fA-F:.]+/[0-9]+$ ]]; then
          echo "'$CIDR' is not a CIDR, eg 203.0.113.7/32" >&2
          exit 1
        fi
      done
    fi
    if [ -n "$CALLER" ]; then
      # before anything is changed: if the address cannot be learnt, the ingress is left as it is
      CALLER_CIDR="$(caller_ip)/32"
      # added to the CIDRs given, else to those the config says
      LISTED_CIDRS="${POLARIS_ALLOWED_CIDRS-$(configured_polaris_cidrs)}"
      case ",$LISTED_CIDRS," in
        *",$CALLER_CIDR,"*) echo "- This machine, $CALLER_CIDR, is already in the list" ;;
        *) POLARIS_ALLOWED_CIDRS="${LISTED_CIDRS:+$LISTED_CIDRS,}$CALLER_CIDR"
           echo "- Admitting this machine, $CALLER_CIDR, as $CALLER_IP_URL sees it" ;;
      esac
    fi
    # the CIDRs the deploy would restrict it to, with the cluster's own addresses, which says where each comes from
    CIDRS=$(restriction_cidrs)
    if [ -z "$CIDRS" ]; then
      echo "No CIDRs to restrict the Polaris ingress to" >&2
      exit 1
    fi
    banner "Restricting the Polaris ingress of $CONTEXT to $CIDRS"
    if [ "$CLOUD" != "aws" ]; then
      kubectl --context "$CONTEXT" patch "$(controller_service "$CONTEXT")" -n ingress-nginx --type merge \
        -p '{"spec":{"externalTrafficPolicy":"Local"}}'
    fi
    kubectl --context "$CONTEXT" annotate ingress polaris-ingress -n default --overwrite "$ANNOTATION=$CIDRS"
    echo "- Until the next deploy, or tofu-apply, which sets it as the config says. This machine must not be among them for the" \
         "network smoke test, and must be for the Polaris steps of setup."
    if [ -n "$CALLER" ]; then
      echo "- Run the command again without --caller to take this machine off the list: Polaris is then restricted to the configured" \
           "CIDRs and the cluster's own addresses"
    fi
    ;;

  remove)
    if [ "$STATE" = "absent" ]; then
      echo "- The Polaris ingress of $CONTEXT is not restricted: nothing to remove"
    else
      banner "Removing the restriction of the Polaris ingress of $CONTEXT (it was ${STATE#present:})"
      kubectl --context "$CONTEXT" annotate ingress polaris-ingress -n default "$ANNOTATION-"
      echo "- Until the next deploy, or tofu-apply, which sets the restriction as the config says"
    fi
    ;;
esac
