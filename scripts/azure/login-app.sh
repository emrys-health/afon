source $AFON_ROOT/scripts/config/secret-source.sh azure

echo "az login as service principal '$AZURE_APP_ID' for tenant '$AZURE_TENANT_ID'"
az login --service-principal -u $AZURE_APP_ID -p $AZURE_APP_SECRET --tenant $AZURE_TENANT_ID

