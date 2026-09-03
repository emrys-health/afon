source $AFON_ROOT/scripts/config/secret-source.sh azure

echo "az login via portal for tenant '$AZURE_TENANT_ID'"
az login --tenant $AZURE_TENANT_ID
