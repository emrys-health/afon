if [ -z "$AFON_HOME" ]; then
  echo "AFON_HOME is not set"
  exit 1
fi

CONFIG_DIR="$AFON_HOME/config"
if [ ! -d "$CONFIG_DIR" ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit 1
fi

# Property renames to apply to every $CONFIG_DIR/*.properties file, "OLD=NEW" per entry.
# Add an entry here whenever a property is renamed, so existing installs can migrate forward.
RENAMES=(
  "KC_HOSTNAME=OIDC_DOMAIN_NAME"
  "KC_PROTOCOL=OIDC_PROTOCOL"
  "KC_BOOTSTRAP_ADMIN_USERNAME=KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME"
  "KC_BOOTSTRAP_ADMIN_PASSWORD=KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD"
  "KC_DB=KEYCLOAK_DB"
  "KC_DB_URL=KEYCLOAK_DB_URL"
  "KC_DB_USERNAME=KEYCLOAK_DB_USERNAME"
  "KC_DB_PASSWORD=KEYCLOAK_DB_PASSWORD"
  "AWS_JUPYTER_DOMAIN=AWS_JUPYTER_DOMAIN_NAME"
  "AWS_S3_EXTERNAL_BUCKET_NAME=AWS_S3_BUCKET_NAME_EXTERNAL"
  "GLUE_S3_ROLE_ARN=AWS_GLUE_ROLE_ARN"
  "GLUE_CATALOG_NAME=AWS_GLUE_CATALOG_NAME"
  "AZURE_DOMAIN_NAME=AZURE_POLARIS_DOMAIN_NAME"
  "AZURE_BUCKET=AZURE_BUCKET_NAME"
  "AZURE_BUCKET_FORBIDDEN=AZURE_BUCKET_NAME_FORBIDDEN"
  "AZURE_EXTERNAL_BUCKET=AZURE_BUCKET_NAME_EXTERNAL"
  "DATABRICKS_TOKEN=AZURE_DATABRICKS_TOKEN"
  "AZURE_CLIENT_ID=AZURE_APP_ID"
  "AZURE_CLIENT_SECRET=***REMOVED***"
  "GCP_DOMAIN_NAME=GCP_POLARIS_DOMAIN_NAME"
  "GCP_EXTERNAL_BUCKET_NAME=GCP_BUCKET_NAME_EXTERNAL"
  "AWS_CATALOG_NAMES=AWS_CATALOG_IDS"
  "AZURE_CATALOG_NAMES=AZURE_CATALOG_IDS"
  "GCP_CATALOG_NAMES=GCP_CATALOG_IDS"
)

shopt -s nullglob
FILES=("$CONFIG_DIR"/*.properties)
shopt -u nullglob
if [ ${#FILES[@]} -eq 0 ]; then
  echo "No *.properties files found in $CONFIG_DIR"
  exit 0
fi

echo "Renaming properties in $CONFIG_DIR"
for FILE in "${FILES[@]}"; do {
  BACKUP="$FILE.bak"
  cp -p "$FILE" "$BACKUP"
  for RENAME in "${RENAMES[@]}"; do {
    OLD_KEY="${RENAME%%=*}"
    NEW_KEY="${RENAME#*=}"
    # \b-anchored so eg <KEY> does not also match inside <KEY>_URL.
    perl -pi -e "s/\b\Q${OLD_KEY}\E\b/${NEW_KEY}/g" "$FILE"
  } done
  if cmp -s "$FILE" "$BACKUP"; then
    rm "$BACKUP"
    echo "$(basename "$FILE"): no properties to rename"
  else
    echo "$(basename "$FILE"): renamed (backup: $(basename "$BACKUP"))"
  fi
} done

echo "Done. Review the diffs (eg: diff \$FILE.bak \$FILE), then run ./afon.sh -a config-validate."
