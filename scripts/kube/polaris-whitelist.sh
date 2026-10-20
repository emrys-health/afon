#!/usr/bin/env bash
# Shows, sets or removes the restriction of who can call the Polaris ingress of a cluster, live, ie by the annotation of its ingress,
# which the next deploy, or `tofu-apply`, sets again as the config says.  It is how to let the machine running `setup` call Polaris
# of a stack deployed with AWS_POLARIS_ALLOWED_CIDRS or AZURE_POLARIS_ALLOWED_CIDRS, and how to restrict it again for the `network`
# smoke test, which needs the machine to be stopped:
#   - the annotation is alb.ingress.kubernetes.io/inbound-cidrs on AWS, and nginx.ingress.kubernetes.io/whitelist-source-range on
#     Azure, which only works with externalTrafficPolicy Local on the ingress controller's service, so `set` sets that too;
#   - what is set is the CIDRs given, else the configured AWS_POLARIS_ALLOWED_CIDRS or AZURE_POLARIS_ALLOWED_CIDRS, plus the
#     cluster's own addresses, as the deploy adds them, so that its pods calling Polaris by its public domain name are not locked out:
#     its egress IP on AWS, or its outbound IP and pod CIDR on Azure.
#
# Usage:
#   ./polaris-whitelist.sh <aws|azure> show
#   ./polaris-whitelist.sh <aws|azure> set [<cidrs>]
#   ./polaris-whitelist.sh <aws|azure> remove
#
#   show     prints how the ingress is restricted, if it is, and for Azure the traffic policy of the ingress controller.
#   set      restricts it, to the comma-separated CIDRs given, eg 203.0.113.7/32,198.51.100.0/24, else the configured ones, plus
#            the cluster's own addresses.
#   remove   removes the restriction, so that Polaris can be called from anywhere, until the next deploy.
#
# Env vars:
#   CONTEXT   kubectl context of the cluster (defaults to the current context), whose kind must be that of the cloud.
#
# Examples:
#   afon-cli.sh azure-update-kube-config tre
#   afon-cli.sh kube-polaris-whitelist azure show
#   afon-cli.sh kube-polaris-whitelist azure remove     # to run setup from this machine
#   afon-cli.sh kube-polaris-whitelist azure set        # to restrict it again, for the network smoke test

set -euo pipefail

AFON_ROOT="${AFON_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

usage() {
  sed -n '/^# Usage:/,/^# Env vars:/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2
}

CLOUD=$(echo "${1:-}" | tr '[:upper:]' '[:lower:]')
ACTION="${2:-}"
case "$CLOUD" in
  aws|azure) ;;
  *) echo "The cloud should be aws or azure, not '${1:-}'" >&2; usage; exit 1 ;;
esac
case "$ACTION" in
  show|remove) [ $# -le 2 ] || { echo "$ACTION takes no CIDRs" >&2; usage; exit 1; } ;;
  set) [ $# -le 3 ] || { usage; exit 1; } ;;
  *) echo "The action should be show, set or remove, not '$ACTION'" >&2; usage; exit 1 ;;
esac

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
  if [ "$CLOUD" = "azure" ]; then
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
    if [ $# -ge 3 ]; then
      POLARIS_ALLOWED_CIDRS="$3"
      for CIDR in ${POLARIS_ALLOWED_CIDRS//,/ }; do
        if ! [[ "$CIDR" =~ ^[0-9a-fA-F:.]+/[0-9]+$ ]]; then
          echo "'$CIDR' is not a CIDR, eg 203.0.113.7/32" >&2
          exit 1
        fi
      done
    fi
    # the CIDRs the deploy would restrict it to, with the cluster's own addresses, which says where each comes from
    CIDRS=$(restriction_cidrs)
    if [ -z "$CIDRS" ]; then
      echo "No CIDRs to restrict the Polaris ingress to" >&2
      exit 1
    fi
    banner "Restricting the Polaris ingress of $CONTEXT to $CIDRS"
    if [ "$CLOUD" = "azure" ]; then
      kubectl --context "$CONTEXT" patch "$(controller_service "$CONTEXT")" -n ingress-nginx --type merge \
        -p '{"spec":{"externalTrafficPolicy":"Local"}}'
    fi
    kubectl --context "$CONTEXT" annotate ingress polaris-ingress -n default --overwrite "$ANNOTATION=$CIDRS"
    echo "- Until the next deploy, or tofu-apply, which sets it as the config says. This machine must not be among them for the" \
         "network smoke test, and must be for the Polaris steps of setup."
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
