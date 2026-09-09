source $AFON_ROOT/scripts/config/afon-source.sh azure

# Resource providers Azure stacks need registered on the subscription,
# so create.sh/tofu apply don't fail with MissingSubscriptionRegistration.
# Requires Contributor at subscription scope since provider registration is a subscription-level action.
# Each provider is required by a specific piece of functionality:
#   Microsoft.Authorization     - role assignments, usually already registered on every subscription
#   Microsoft.Storage           - create.sh (storage account)
#   Microsoft.Network           - AKS stack (VNet, NSG, subnet)
#   Microsoft.ManagedIdentity   - AKS stack (aks-identity)
#   Microsoft.ContainerService  - AKS stack (the cluster itself)
#   Microsoft.Compute           - AKS stack (node pools)
AZURE_RESOURCE_PROVIDERS="Microsoft.Authorization Microsoft.Storage Microsoft.Network Microsoft.ManagedIdentity Microsoft.ContainerService Microsoft.Compute"

echo "- Checking resource provider registrations for subscription '$AZURE_SUBSCRIPTION'"

for ns in $AZURE_RESOURCE_PROVIDERS; do
  STATE="$(az provider show --namespace "$ns" --query registrationState -o tsv 2>/dev/null)"
  if [ "$STATE" = "Registered" ]; then
    echo "-- $ns already registered, skipping"
  else
    echo "-- Registering $ns (was: ${STATE:-NotFound}), waiting for it to complete..."
    az provider register --namespace "$ns" --wait || exit 1
  fi
done

echo "- All resource providers are registered."
