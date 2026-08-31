source $AFON_ROOT/scripts/config/afon-source.sh azure

echo "- Setting subscription $AZURE_SUBSCRIPTION"
az account set --subscription $AZURE_SUBSCRIPTION
USER_NAME=$(az account show --query "user.name" -o tsv)
echo "- Azure account for user '$USER_NAME':"
az account show --output table
