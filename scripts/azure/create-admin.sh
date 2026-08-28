APP_NAME="${1:-AfonMultiTenantApp}"
echo "- Creating app $APP_NAME"

APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
if [ -z "$APP_IDS" ]; then
  echo "- No existing $APP_NAME found, creating one"
  az ad app create  --display-name "$APP_NAME"  --sign-in-audience "AzureADMultipleOrgs" || exit 1
  echo "Waiting 30s for app to be registered..."
  sleep 30
  APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
else
  echo "- Found existing $APP_NAME app(s), reusing rather than creating a duplicate:"
  echo "$APP_IDS"
fi

APP_ID=$(echo "$APP_IDS" | head -n1)
if [ -z "$APP_ID" ]; then
  echo "-- Failed to find or create $APP_NAME"
  exit 1
fi
if [ "$(echo "$APP_IDS" | wc -l)" -gt 1 ]; then
  echo "-- As multiple $APP_NAME apps exist, using the first one: $APP_ID"
  echo "-- Run destroy-admin.sh to remove ALL of them, then re-run create-admin.sh for a clean single app."
fi

if az ad sp show --id $APP_ID -o none 2>/dev/null; then
  echo "- Service principal for $APP_ID already exists, skipping"
else
  echo "- Creating service principal for $APP_ID"
  az ad sp create --id $APP_ID || exit 1
fi

echo "APP_ID=$APP_ID"
echo "- export APP_ID=$APP_ID before running create.sh to finish setting up the Azure env."

# App credential reset not idempotent by design: Azure never re-exposes an existing secret's value,
# so reset command must always be run to print a new secret, invalidating any previous secret.
APP_CREDENTIAL_RESET="${APP_CREDENTIAL_RESET:-}"
if [ -n "$APP_CREDENTIAL_RESET" ]; then
  echo "- Resetting app credential."
  echo "- From the output below, add the following properties in your config/secret-azure.properties"
  echo "AZURE_CLIENT_SECRET=<password>"
  az ad app credential reset --id $APP_ID
else
  echo "- Not resetting app credentials, as APP_CREDENTIAL_RESET is not set."
fi
