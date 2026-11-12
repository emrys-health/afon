#!/usr/bin/env bash
# Sourced by the Azure scripts that need the AKS cluster that serves Polaris, eg azure/identity.sh.
#
# azure_polaris_cluster <resource group> [<stack, by its id or its role in the cloud>]
#   Sets STACK_CLUSTER_NAME to the cluster <stack>-<CLUSTER_NAME> of the stack given, else of the one running among the stacks that deploy
#   Polaris, found by their AKS clusters in the resource group: as they hold a common exclusive resource, eg a domain name, only one of
#   them runs at a time.  Returns 1, saying why, if there is none, or more than one: give the stack then.
#   The stacks, and what is needed of a stack given, are found as the launcher does, or as scripts/stack/needs-source.sh asks.
azure_polaris_cluster() {
  local resource_group="$1" given="${2:-}" ids type clusters cluster found="" count
  if [ -z "${CLUSTER_NAME:-}" ]; then
    echo "-- CLUSTER_NAME is not set, cannot name the cluster: set it in afon.properties, or give the cluster" >&2
    return 1
  fi
  source $AFON_ROOT/scripts/stack/needs-source.sh
  if [ -n "$given" ]; then
    case "$(echo "$given" | tr '[:upper:]' '[:lower:]')" in
      azure-identity|azure-catalog|azure-tre) ;;
      azure-*) STACK_CLUSTER_NAME="${given#azure-}-$CLUSTER_NAME"; return 0 ;;
    esac
    stack_needs azure "$given" || return 1
    STACK_CLUSTER_NAME="${AFON_STACK_ID#azure-}-$CLUSTER_NAME"
    return 0
  fi
  stack_needs azure none Polaris || return 1
  ids="${AFON_STACK_CANDIDATES:-}"
  if ! clusters="$(az aks list --resource-group "$resource_group" --query "[].name" -o tsv 2>/dev/null)"; then
    echo "-- Could not list the AKS clusters in resource group $resource_group: is the az login allowed to read them?" >&2
    return 1
  fi
  for id in $ids; do
    type="${id#azure-}"
    for cluster in $clusters; do
      if [ "$cluster" = "$type-$CLUSTER_NAME" ]; then
        found="$found $type"
      fi
    done
  done
  count="$(echo $found | wc -w | tr -d ' ')"
  case "$count" in
    0) echo "-- No stack serving Polaris found, ie no AKS cluster <stack>-$CLUSTER_NAME in $resource_group, for stacks: ${ids:-none}" >&2
       return 1 ;;
    1) STACK_CLUSTER_NAME="$(echo $found)-$CLUSTER_NAME" ;;
    *) echo "-- More than one stack serving Polaris found:$found.  Give the stack, by its id or its role" >&2
       return 1 ;;
  esac
}
