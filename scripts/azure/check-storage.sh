source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

# Checks that AZURE_CONNECTION_STRING is the connection string of the storage account AZURE_STORAGE_ACCOUNT, as it is
# not once the account has been recreated, eg with its resource group, which gives it new keys: what authenticates with
# it, eg Gitea and the log shipper, then fails with 403 AuthenticationFailed only when it starts.  The string is never
# printed.  Run on its own, or by create.sh, which sources it.

# The raw value of AZURE_CONNECTION_STRING, read from the files, not from the environment: they are sourced by the shell, whose
# assignment ends at the first ';' of the connection string, which Java, reading them as properties, takes whole.
raw_connection_string() {
  local FILE
  for FILE in "$AFON_HOME/config/secret.properties" "$AFON_HOME/config/secret-azure.properties"; do
    [ -f "$FILE" ] && grep -E '^[[:space:]]*AZURE_CONNECTION_STRING[[:space:]]*=' "$FILE"
  done | tail -1 | cut -d= -f2- | tr -d '\r' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

check_storage_fail() {
  echo "-- $1"
  if [ "${BASH_SOURCE[0]}" != "$0" ]; then
    return 1
  fi
  exit 1
}

check_storage() {
  local CONNECTION_STRING ACCOUNT_NAME ERR_FILE
  CONNECTION_STRING="$(raw_connection_string)"
  echo "- Checking that AZURE_CONNECTION_STRING is the connection string of storage account '$AZURE_STORAGE_ACCOUNT'"
  if [ -z "$CONNECTION_STRING" ]; then
    check_storage_fail "AZURE_CONNECTION_STRING is not set.  Set it in secret-azure.properties to the output of: az storage account show-connection-string --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_STORAGE_ACCOUNT --query connectionString -o tsv"
    return $?
  fi
  ACCOUNT_NAME=$(echo "$CONNECTION_STRING" | tr ';' '\n' | sed -n 's/^AccountName=//p')
  if [ "$ACCOUNT_NAME" != "$AZURE_STORAGE_ACCOUNT" ]; then
    check_storage_fail "AZURE_CONNECTION_STRING is for storage account '${ACCOUNT_NAME:-?}', not '$AZURE_STORAGE_ACCOUNT' (AZURE_STORAGE_ACCOUNT)"
    return $?
  fi
  ERR_FILE=$(mktemp)
  # Through the environment, so that the key is not on the command line.  Listing a container needs the key to be valid.
  if AZURE_STORAGE_CONNECTION_STRING="$CONNECTION_STRING" az storage container list --auth-mode key --num-results 1 -o none 2>"$ERR_FILE"; then
    rm -f "$ERR_FILE"
    echo "- AZURE_CONNECTION_STRING is valid for storage account '$AZURE_STORAGE_ACCOUNT'"
    return 0
  fi
  if grep -qi "AuthenticationFailed\|MAC signature\|Authentication failure\|invalid account key\|403" "$ERR_FILE"; then
    rm -f "$ERR_FILE"
    check_storage_fail "The key in AZURE_CONNECTION_STRING is not accepted by storage account '$AZURE_STORAGE_ACCOUNT', eg because the account was recreated and so has new keys.  Set it in secret-azure.properties to the output of: az storage account show-connection-string --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_STORAGE_ACCOUNT --query connectionString -o tsv"
    return $?
  fi
  echo "-- Could not check storage account '$AZURE_STORAGE_ACCOUNT':"
  sed 's/^/--   /' "$ERR_FILE"
  rm -f "$ERR_FILE"
  check_storage_fail "AZURE_CONNECTION_STRING could not be verified"
  return $?
}

check_storage
