source $AFON_ROOT/scripts/config/secret-source.sh azure

echo "az login as service principal '$AZURE_CLIENT_ID' for tenant '$AZURE_TENANT_ID'"
az login --service-principal -u $AZURE_CLIENT_ID -p $AZURE_CLIENT_SECRET --tenant $AZURE_TENANT_ID

