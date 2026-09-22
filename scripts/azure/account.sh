echo "- Azure account:"
az account show --query "user.name" -o tsv
