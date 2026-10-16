source $AFON_ROOT/scripts/config/secret-source.sh azure

echo "az login via portal for tenant '$AZURE_TENANT_ID'"
az login --tenant $AZURE_TENANT_ID || exit 1

# Selects the subscription of the env, which the login does not, so that no other step is needed
source $AFON_ROOT/scripts/azure/account.sh
