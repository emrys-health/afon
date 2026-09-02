APP_NAME="${1:-AfonMultiTenantApp}"
echo "- destroy-admin.sh '$APP_NAME'"
echo "- Run destroy.sh <false> first (with APP_ID set) to destroy role assignments, storage and resource group, before removing the app."

APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
if [ -z "$APP_IDS" ]; then
  echo "-- No app '$APP_NAME' found, skipping"
else
  echo "-- Found '$APP_NAME' apps(s):"
  echo "$APP_IDS"
  for APP_ID in $APP_IDS; do
    echo "- Deleting service principal for app '$APP_ID'"
    az ad sp delete --id $APP_ID
    echo "- Deleting app '$APP_ID'"
    az ad app delete --id $APP_ID
  done
fi
