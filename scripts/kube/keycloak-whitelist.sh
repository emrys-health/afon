#!/usr/bin/env bash
# Shows, sets or removes the restriction of who can reach the admin side of the Keycloak ingress of a cluster, live, ie by the annotation
# of its ingress, which the next deploy, or `tofu-apply`, sets again as the config says.  It is how to let the machine running
# `manage-identity` reach the Keycloak admin API of a stack deployed with AZURE_ or GCP_KEYCLOAK_ALLOWED_CIDRS, and how to restrict it
# again:
#   - the restricted ingress is keycloak-ingress, of `/`, with the annotation nginx.ingress.kubernetes.io/whitelist-source-range, which
#     only works with externalTrafficPolicy Local on the ingress controller's service, so `set` sets that too.  The paths that users log
#     in with, in keycloak-ingress-open, stay open to all, and are not touched, see docs/cluster.md#restricting-who-can-reach-keycloak;
#   - what is set is the CIDRs given, else the configured AZURE_ or GCP_KEYCLOAK_ALLOWED_CIDRS, plus the cluster's own addresses, as the
#     deploy adds them, so that its pods calling Keycloak by its public domain name are not locked out: its outbound IP and pod CIDR on
#     Azure, or its pinned egress IP, pod range and node subnet on GCP;
#   - with --caller, the address of this machine is added to those, which is how to let it call the admin API, without taking the
#     restriction off for everybody.  `set` again, without --caller, takes it off.
# It does nothing on AWS, where the restriction is not an annotation of the ingress but the listener rules of the ALB, which only a deploy sets.
#
# Usage:
#   ./keycloak-whitelist.sh <azure|gcp> show [--stack <stack-id>]
#   ./keycloak-whitelist.sh <azure|gcp> set [--caller] [--stack <stack-id>] [<cidrs>]
#   ./keycloak-whitelist.sh <azure|gcp> remove [--stack <stack-id>]
#
#   show     prints how the ingress is restricted, if it is, and the traffic policy of the ingress controller.
#   set      restricts it, to the comma-separated CIDRs given, eg 203.0.113.7/32,198.51.100.0/24, else the configured ones, plus the
#            cluster's own addresses.  With --caller, the IPv4 address of this machine, as https://ifconfig.me sees it, is added to those:
#            to the CIDRs given, else to the configured ones.  `set` again, without --caller, takes it off, as it sets the list from the
#            config, or from the CIDRs given, and not from the annotation as it is.  It fails, changing nothing, if the address cannot be
#            learnt.  Mind that it is the address of the route to the internet, so it is not what Keycloak sees through a VPN or a proxy
#            that routes to the cluster differently.
#            If nothing is configured nor given, Keycloak is open to all, as the deploy leaves it: `set` takes the restriction off, and
#            --caller does so too, saying that there is no restriction to admit this machine to, so that it can be run whatever the config.
#   remove   removes the restriction, so that Keycloak can be reached from anywhere, until the next deploy.
#
# With --stack, the cluster is that of the stack, by its id, eg azure-keycloak, as its kubeconfig in the cdktf dir has it, written by
# `<cloud>-update-kube-config`, whatever KUBECONFIG and the current context are; as `manage-project up` does, for the identity project's.
#
# Env vars:
#   CONTEXT         kubectl context of the cluster (defaults to the current context), whose kind must be that of the cloud.
#   CALLER_PREFIX   the prefix length of the range admitted by --caller, around the address of this machine (defaults to 32, ie that address
#                   alone): 24 admits the 256 addresses of its /24, for a machine whose connections leave from different ones of a range.
#   CALLER_IP_URL   the service that says the address it is called from (defaults to https://ifconfig.me).
#
# Examples:
#   afon-cli.sh azure-update-kube-config identity
#   afon-cli.sh kube-keycloak-whitelist azure show
#   afon-cli.sh kube-keycloak-whitelist azure set --caller  # to run manage-identity from this machine, without taking the restriction off
#   afon-cli.sh kube-keycloak-whitelist azure set           # to take this machine off it again
#   afon-cli.sh kube-keycloak-whitelist azure remove        # or to run manage-identity from this machine, with the restriction off

set -euo pipefail

AFON_ROOT="${AFON_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

usage() {
  sed -n '/^# Usage:/,/^# Env vars:/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2
}

CLOUD=$(echo "${1:-}" | tr '[:upper:]' '[:lower:]')
CLOUD_UPPER=$(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')
ACTION="${2:-}"
case "$CLOUD" in
  azure|gcp) ;;
  aws) echo "On AWS Keycloak is restricted by the listener rules of its ALB, which only a deploy sets: set AWS_KEYCLOAK_ALLOWED_CIDRS and deploy" >&2
       exit 1 ;;
  *) echo "The cloud should be azure or gcp, not '${1:-}'" >&2; usage; exit 1 ;;
esac
case "$ACTION" in
  show|set|remove) ;;
  *) echo "The action should be show, set or remove, not '$ACTION'" >&2; usage; exit 1 ;;
esac

# what follows the action: for set, the option --caller and the CIDRs to restrict it to, in either order
CALLER=
STACK=
EXPECT_STACK=
GIVEN=
HAVE_GIVEN=
shift $(( $# < 2 ? $# : 2 ))
for ARG in "$@"; do
  if [ -n "$EXPECT_STACK" ]; then STACK="$ARG"; EXPECT_STACK=; continue; fi
  case "$ARG" in
    --caller) CALLER=true ;;
    --stack) EXPECT_STACK=true ;;
    -*) echo "Unknown option: $ARG" >&2; usage; exit 1 ;;
    *) [ -z "$HAVE_GIVEN" ] || { echo "Only one list of CIDRs may be given" >&2; usage; exit 1; }
       GIVEN="$ARG"; HAVE_GIVEN=true ;;
  esac
done
if [ -n "$EXPECT_STACK" ]; then echo "--stack takes a stack id" >&2; usage; exit 1; fi
if [ "$ACTION" != "set" ] && { [ -n "$CALLER" ] || [ -n "$HAVE_GIVEN" ]; }; then
  echo "$ACTION takes no CIDRs or --caller" >&2; usage; exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi

# for what is the same as for Polaris: the properties of the config, the ingress controller and the cluster's addresses
source "$AFON_ROOT/scripts/kube/polaris-whitelist-source.sh"

if [ -n "$STACK" ]; then
  source "$AFON_ROOT/scripts/config/afon-source.sh" "$CLOUD"
  source "$AFON_ROOT/scripts/kube/kubeconfig-source.sh"
  KUBECONFIG="$(kubeconfig_path "$STACK")"
  if [ ! -f "$KUBECONFIG" ]; then
    echo "There is no kubeconfig of the stack $STACK, $KUBECONFIG: write it with 'afon-cli.sh $CLOUD-update-kube-config $STACK'" >&2
    exit 1
  fi
  export KUBECONFIG
fi
CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
ANNOTATION="nginx.ingress.kubernetes.io/whitelist-source-range"
INGRESS="keycloak-ingress"

# The cluster is the one of the cloud, as changing the ingress of another one is not what was asked.  The current context is not
# always what it is thought to be, eg after switching env.
case "$CLOUD" in
  gcp)
    case "$CONTEXT" in
      gke_*) ;;
      *) echo "The kubectl context '$CONTEXT' is not a GKE cluster: point kubectl at the GCP one, eg with 'afon-cli.sh gcp-update-kube-config identity'" >&2
         exit 1 ;;
    esac ;;
  azure)
    if [ -z "$(controller_service "$CONTEXT")" ]; then
      echo "The kubectl context '$CONTEXT' has no nginx ingress controller, as the Azure stacks have: point kubectl at the AKS one," \
           "eg with 'afon-cli.sh azure-update-kube-config identity'" >&2
      exit 1
    fi ;;
esac

# Prints the state of the annotation of the Keycloak ingress, as it is in the cluster: "present:<value>" if it is set, even to the empty
# string, "absent" if it is not, "missing" if there is no Keycloak ingress.
keycloak_ingress_annotation() {
  local out
  if ! out=$(kubectl --context "$CONTEXT" get ingress "$INGRESS" -n default \
               -o go-template="{{range \$k, \$v := .metadata.annotations}}{{if eq \$k \"$ANNOTATION\"}}present:{{\$v}}{{end}}{{end}}" \
               2>/dev/null); then
    echo "missing"
    return 0
  fi
  echo "${out:-absent}"
}

STATE=$(keycloak_ingress_annotation)
if [ "$STATE" = "missing" ]; then
  echo "There is no Keycloak ingress ($INGRESS in the default namespace) in the kubectl context '$CONTEXT'" >&2
  exit 1
fi

CALLER_IP_URL="${CALLER_IP_URL:-https://ifconfig.me}"

# Prints the IPv4 address of this machine, as CALLER_IP_URL sees it, or fails.  Asked for IPv4 only (curl -4): the domain names of Keycloak
# only have IPv4 addresses, so that is the address a call to Keycloak comes from, whereas a dual-stack machine is told its IPv6 one.
caller_ip() {
  local ip
  ip="$(curl -4 -s --max-time 10 "$CALLER_IP_URL" 2>/dev/null | tr -d '[:space:]')" || true
  if ! [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    echo "Could not learn the IPv4 address of this machine from $CALLER_IP_URL (got '${ip:-nothing}'): is it reachable over IPv4?" >&2
    exit 1
  fi
  echo "$ip"
}

# Prints the CIDR of the address $1 with the prefix length $2, eg 45.91.20.27 and 24 give 45.91.20.0/24, which is how to admit a machine whose
# connections leave from several addresses of a range, as those of some providers' NATs do, each of them from another.
caller_network() {
  local ip="$1" bits="$2" a b c d n mask
  case "$bits" in
    ''|*[!0-9]*) echo "CALLER_PREFIX should be a number of bits, from 8 to 32, not '$bits'" >&2; return 1 ;;
  esac
  if [ "$bits" -lt 8 ] || [ "$bits" -gt 32 ]; then
    echo "CALLER_PREFIX should be from 8 to 32, not $bits: a wider range admits too much" >&2
    return 1
  fi
  IFS=. read -r a b c d <<< "$ip"
  n=$(( (a << 24) | (b << 16) | (c << 8) | d ))
  mask=$(( bits == 0 ? 0 : (0xFFFFFFFF << (32 - bits)) & 0xFFFFFFFF ))
  n=$(( n & mask ))
  echo "$(( (n >> 24) & 255 )).$(( (n >> 16) & 255 )).$(( (n >> 8) & 255 )).$(( n & 255 ))/$bits"
}

# Prints the CIDR of an address or CIDR: an address without a / is that of the address alone, as the deploy takes it.
as_cidr() {
  case "$1" in
    */*) echo "$1" ;;
    *:*) echo "$1/128" ;;
    *) echo "$1/32" ;;
  esac
}

# Prints $1, a comma-separated list, with the CIDR $2 added if it is not in it.
with_cidr() {
  case ",$1," in
    *",$2,"*) echo "$1" ;;
    *) echo "${1:+$1,}$2" ;;
  esac
}

# Prints the CIDRs that the config says to admit to the Keycloak of the $CLOUD, ie AZURE_ or GCP_KEYCLOAK_ALLOWED_CIDRS, as CIDRs, or
# nothing if it is not set.
configured_keycloak_cidrs() {
  local csv cidr cidrs=
  case "$CLOUD" in
    azure) csv=$(azure_property AZURE_KEYCLOAK_ALLOWED_CIDRS) ;;
    gcp) csv=$(gcp_property GCP_KEYCLOAK_ALLOWED_CIDRS) ;;
  esac
  for cidr in ${csv//,/ }; do
    cidrs=$(with_cidr "$cidrs" "$(as_cidr "$cidr")")
  done
  echo "$cidrs"
}

# Prints the CIDRs that the Keycloak ingress is restricted to: those given, else the configured ones, with this machine's if CALLER, and
# the cluster's own addresses, as the deploy adds them, but only if there is a restriction at all.  Prints nothing if there is none.
# Says, to stderr, where each comes from.
restriction_cidrs() {
  local listed cidrs cidr outbound configured_ip own_source from
  if [ -n "$HAVE_GIVEN" ]; then
    listed=
    for cidr in ${GIVEN//,/ }; do
      if ! [[ "$cidr" =~ ^[0-9a-fA-F:.]+(/[0-9]+)?$ ]]; then
        echo "'$cidr' is not a CIDR, eg 203.0.113.7/32" >&2
        exit 1
      fi
      listed=$(with_cidr "$listed" "$(as_cidr "$cidr")")
    done
    from="given"
  else
    listed=$(configured_keycloak_cidrs)
    from="${CLOUD_UPPER}_KEYCLOAK_ALLOWED_CIDRS in $AFON_CONFIG_DIR/afon-$CLOUD.properties"
  fi
  if [ -n "$CALLER" ]; then
    if [ -z "$listed" ]; then
      echo "- Keycloak is not restricted (nothing is given, and ${CLOUD_UPPER}_KEYCLOAK_ALLOWED_CIDRS is not set): there is no restriction to admit" \
           "this machine to" >&2
      return 0
    fi
    # before anything is changed: if the address cannot be learnt, the ingress is left as it is
    # explicitly, as this runs in a command substitution, where a failure does not stop the script by itself
    local caller_cidr
    caller_cidr="$(caller_ip)" || exit 1
    caller_cidr="$(caller_network "$caller_cidr" "${CALLER_PREFIX:-32}")" || exit 1
    case ",$listed," in
      *",$caller_cidr,"*) echo "- This machine, $caller_cidr, is already in the list" >&2 ;;
      *) listed=$(with_cidr "$listed" "$caller_cidr")
         echo "- Admitting this machine, $caller_cidr, as $CALLER_IP_URL sees it" >&2 ;;
    esac
  fi
  if [ -z "$listed" ]; then
    return 0
  fi
  cidrs="$listed"
  case "$CLOUD" in
    azure)
      outbound=$(azure_cluster_outbound_ip)
      configured_ip=$(azure_property AZURE_EGRESS_IP)
      own_source="the first effective outbound IP of the cluster's load balancer, with az"
      if [ -z "$outbound" ]; then
        outbound="$configured_ip"
        own_source="AZURE_EGRESS_IP in $AFON_CONFIG_DIR/afon-azure.properties, as no outbound IP was found with az"
        [ -n "$outbound" ] || warning "no outbound IP found for kubectl context $CONTEXT: not including it"
      fi
      [ -z "$outbound" ] || cidrs=$(with_cidr "$cidrs" "$outbound/32")
      cidrs=$(with_cidr "$cidrs" "$AZURE_POD_CIDR")
      banner "Keycloak ingress CIDRs, from:" >&2
      echo "  listed: $listed ($from)" >&2
      echo "  outbound IP: ${outbound:-not found} ($own_source)" >&2
      echo "  pod CIDR: $AZURE_POD_CIDR" >&2
      ;;
    gcp)
      configured_ip=$(gcp_property GCP_EGRESS_IP)
      [ -z "$configured_ip" ] || cidrs=$(with_cidr "$cidrs" "$configured_ip/32")
      cidrs=$(with_cidr "$(with_cidr "$cidrs" "$GCP_POD_CIDR")" "$GCP_NODE_CIDR")
      banner "Keycloak ingress CIDRs, from:" >&2
      echo "  listed: $listed ($from)" >&2
      echo "  egress IP: ${configured_ip:-none} (GCP_EGRESS_IP), pod range and node subnet: $GCP_POD_CIDR, $GCP_NODE_CIDR" >&2
      ;;
  esac
  echo "$cidrs"
}

# Prints the traffic policy of the ingress controller
controller_policy() {
  kubectl --context "$CONTEXT" get "$(controller_service "$CONTEXT")" -n ingress-nginx -o jsonpath='{.spec.externalTrafficPolicy}'
}

show() {
  echo "- Cluster: $CONTEXT ($CLOUD)"
  case "$STATE" in
    present:?*) echo "- The Keycloak ingress is restricted to ${STATE#present:} ($ANNOTATION)" ;;
    present:)   echo "- The Keycloak ingress has $ANNOTATION set to nothing" ;;
    *)          echo "- The Keycloak ingress is not restricted: $ANNOTATION is not set" ;;
  esac
  local policy
  policy=$(controller_policy)
  if [ "$policy" = "Local" ]; then
    echo "- The traffic policy of its ingress controller is Local, which keeps the callers' IPs"
  else
    echo "- WARNING: the traffic policy of its ingress controller is $policy, not Local, so it does not see the callers' IPs and" \
         "its restriction does not work: set restricts it as it should be"
  fi
}

remove_restriction() {
  if [ "$STATE" = "absent" ]; then
    echo "- The Keycloak ingress of $CONTEXT is not restricted: nothing to remove"
  else
    banner "Removing the restriction of the Keycloak ingress of $CONTEXT (it was ${STATE#present:})"
    kubectl --context "$CONTEXT" annotate ingress "$INGRESS" -n default "$ANNOTATION-"
    echo "- Until the next deploy, or tofu-apply, which sets the restriction as the config says"
  fi
}

# With --caller, waits until Keycloak answers this machine, as the ingress controller applies the annotation some seconds after it is set,
# and the caller, eg manage-identity, would be answered 403 by nginx until then.  Gives up after WAIT_SECONDS, which is not an error: it
# only says so, as the address that Keycloak sees may not be the one that was admitted, eg through a VPN.
wait_until_admitted() {
  local domain domain_var="${CLOUD_UPPER}_OIDC_DOMAIN_NAME" url code waited=0 limit="${WAIT_SECONDS:-60}"
  # as the config has it, with its names, eg ${ENV_NAME}, expanded, which the raw property of the file does not
  [ -n "$STACK" ] || source "$AFON_ROOT/scripts/config/afon-source.sh" "$CLOUD"
  domain="${!domain_var:-}"
  if [ -z "$domain" ] || ! command -v curl >/dev/null 2>&1; then
    echo "- Not waiting for the ingress to admit this machine: no ${CLOUD_UPPER}_OIDC_DOMAIN_NAME in the config, or no curl" >&2
    return 0
  fi
  url="https://$domain/realms/master/protocol/openid-connect/token"
  echo "- Waiting for $domain to admit this machine (up to ${limit}s)" >&2
  while :; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST --max-time 10 "$url" 2>/dev/null) || code=000
    if [ "$code" != "403" ]; then
      echo "- $domain answers this machine ($code)" >&2
      return 0
    fi
    if [ "$waited" -ge "$limit" ]; then
      echo "- WARNING: $domain still answers 403 after ${limit}s: Keycloak may not see this machine as the address that was admitted" >&2
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
}

case "$ACTION" in
  show)
    show
    ;;

  set)
    CIDRS=$(restriction_cidrs)
    if [ -z "$CIDRS" ]; then
      # nothing is configured nor given: as the deploy leaves it, Keycloak is open to all
      echo "- No CIDRs to restrict the Keycloak ingress to (${CLOUD_UPPER}_KEYCLOAK_ALLOWED_CIDRS is not set, and none is given): it is open to all, as the" \
           "deploy leaves it"
      remove_restriction
      exit 0
    fi
    banner "Restricting the Keycloak ingress of $CONTEXT to $CIDRS"
    kubectl --context "$CONTEXT" patch "$(controller_service "$CONTEXT")" -n ingress-nginx --type merge \
      -p '{"spec":{"externalTrafficPolicy":"Local"}}'
    kubectl --context "$CONTEXT" annotate ingress "$INGRESS" -n default --overwrite "$ANNOTATION=$CIDRS"
    echo "- Until the next deploy, or tofu-apply, which sets it as the config says."
    if [ -n "$CALLER" ]; then
      wait_until_admitted
      echo "- Run the command again without --caller to take this machine off the list: Keycloak is then restricted to the configured" \
           "CIDRs and the cluster's own addresses"
    fi
    ;;

  remove)
    remove_restriction
    ;;
esac
