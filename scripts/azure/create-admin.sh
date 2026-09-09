APP_NAME="${1:-AfonMultiTenantApp}"
echo "- Creating app '$APP_NAME'"

APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
if [ -z "$APP_IDS" ]; then
  echo "- No existing app '$APP_NAME' found, creating one"
  az ad app create --display-name "$APP_NAME" --sign-in-audience "AzureADMultipleOrgs" || exit 1
  echo "Waiting 30s for app to be registered..."
  sleep 30
  APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
else
  echo "- Found existing app(s), reusing rather than creating a duplicate:"
  echo "$APP_IDS"
fi

APP_ID=$(echo "$APP_IDS" | head -n1)
if [ -z "$APP_ID" ]; then
  echo "-- Failed to find or create app '$APP_NAME'"
  exit 1
fi
if [ "$(echo "$APP_IDS" | wc -l)" -gt 1 ]; then
  echo "-- As multiple apps exist, using the first one: '$APP_ID'"
  echo "-- Run destroy-admin.sh to remove ALL of them, then re-run create-admin.sh for a clean single app."
fi

APP_SP_ID=$(az ad sp show --id $APP_ID --query id -o tsv 2>/dev/null) || true
if [ -n "$APP_SP_ID" ]; then
  echo "- Service principal for app '$APP_ID' already exists, skipping"
else
  echo "- Creating service principal for app '$APP_ID'"
  # Newly created apps can take a while to replicate across Azure AD, so az ad sp create
  # may transiently fail with "Resource ... does not exist" right after app creation. Retry.
  SP_CREATE_ATTEMPTS=12
  SP_CREATE_DELAY=10
  for i in $(seq 1 $SP_CREATE_ATTEMPTS); do
    APP_SP_ID=$(az ad sp create --id $APP_ID --query id -o tsv 2>/dev/null) && [ -n "$APP_SP_ID" ] && break
    if [ "$i" -eq "$SP_CREATE_ATTEMPTS" ]; then
      echo "-- Failed to create service principal for app '$APP_ID' after $SP_CREATE_ATTEMPTS attempts"
      exit 1
    fi
    echo "-- az ad sp create failed, likely AD replication delay, retrying in ${SP_CREATE_DELAY}s (attempt $i/$SP_CREATE_ATTEMPTS)..."
    sleep $SP_CREATE_DELAY
  done
fi

echo "- export APP_ID and APP_SP_ID before running create.sh to finish setting up the Azure env."
echo "- APP_ID=$APP_ID"
echo "- APP_SP_ID=$APP_SP_ID"

# App credential reset not idempotent by design: Azure never re-exposes an existing secret's value,
# so reset command must always be run to print a new secret, invalidating any previous secret.
APP_CREDENTIAL_RESET="${APP_CREDENTIAL_RESET:-}"
if [ -n "$APP_CREDENTIAL_RESET" ]; then
  echo "- Resetting app credential."
  echo "- From the output below, add the following properties in your config/secret-azure.properties"
  echo "AZURE_APP_SECRET=<password>"
  az ad app credential reset --id $APP_ID
else
  echo "- Not resetting app credentials, as APP_CREDENTIAL_RESET is not set."
fi
