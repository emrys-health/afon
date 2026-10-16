# Shared by the Azure status scripts, eg azure/polaris.sh and azure/keycloak.sh, which source it after the source of
# what they check, eg kube/polaris-source.sh.  Not run on its own.

# azure_status_find_stack <stack id given, or empty> <stack ids>...: sets STACK_TYPE, and so STATUS_CLUSTER_NAME, to the
# type and the cluster <stack>-<CLUSTER_NAME> of the stack given, else of the one running among the stacks, found by
# its AKS cluster: as they hold a common exclusive resource, eg a domain name, only one of them runs at a time.  Reports
# FAILED if none runs, leaving STACK_TYPE and STATUS_CLUSTER_NAME empty, and could not verify if more than one does.
# The launcher gives the stack, or else the stacks that can serve what the script checks, see ScriptMain.
azure_status_find_stack() {
  local given="$1" ids types="" clusters found="" type cluster id
  shift
  ids="$*"
  for id in $ids; do
    types="$types ${id#azure-}"
  done
  types="${types# }"
  STACK_TYPE=
  if [ -n "$given" ]; then
    STACK_TYPE="${given#azure-}"
  else
    if ! clusters=$(az aks list --resource-group "$AZURE_RESOURCE_GROUP" --query "[].name" --output tsv 2>"$STATUS_ERR"); then
      status_cannot_verify "- Could not list the AKS clusters in resource group $AZURE_RESOURCE_GROUP:"
    fi
    for type in $types; do
      for cluster in $clusters; do
        if [ "$cluster" == "$type-$CLUSTER_NAME" ]; then
          found="$found $type"
        fi
      done
    done
    case $(echo $found | wc -w | tr -d ' ') in
      0) status_failed "No stack serving $STATUS_SUBJECT found, ie no AKS cluster <stack>-$CLUSTER_NAME, for stacks: ${ids:-none}" ;;
      1) STACK_TYPE=$(echo $found) ;;
      *) status_cannot_verify "- More than one stack serving $STATUS_SUBJECT found:$found.  Give the stack as argument, by its id or its role, eg ${ids%% *}" ;;
    esac
  fi
  if [ -n "$STACK_TYPE" ]; then
    STATUS_CLUSTER_NAME="$STACK_TYPE-$CLUSTER_NAME"
  else
    STATUS_CLUSTER_NAME=
  fi
}

# azure_status_check_cluster: the AKS network and the credentials of the cluster found, if any
azure_status_check_cluster() {
  if [ -z "$STATUS_CLUSTER_NAME" ]; then
    return 0
  fi
  local vnets
  if vnets=$(az network vnet list --resource-group "$AZURE_RESOURCE_GROUP" --query "[?name=='$STACK_TYPE-aks-vnet'].name" --output tsv 2>"$STATUS_ERR"); then
    status_check_network "AKS network" "$vnets"
  else
    status_cannot_verify "- Could not list the networks in resource group $AZURE_RESOURCE_GROUP:"
  fi

  status_get_credentials AKS "ResourceNotFound|could not be found|not found" \
    az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$STATUS_CLUSTER_NAME" --file "$STATUS_KUBECONFIG"

  # The cluster uses Azure RBAC, so kubectl needs the kubeconfig converted to use the az login, as the
  # AzureCloudProvider does when it deploys.
  if [ "$STATUS_CLUSTER_UP" = "true" ]; then
    if ! kubelogin convert-kubeconfig -l azurecli --kubeconfig "$STATUS_KUBECONFIG" >/dev/null 2>"$STATUS_ERR"; then
      status_cannot_verify "- Could not convert the AKS kubeconfig to use the az login:"
    fi
  fi
}
