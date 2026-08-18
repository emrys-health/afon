VNETS=$(az network vnet list --query "[?name=='aks-vnet' && contains(resourceGroup, 'polaris')].name" --output tsv)
if [ -n "$VNETS" ]; then
  echo "Azure Polaris running: $VNETS"
else
  echo "No Azure Polaris running"
fi
