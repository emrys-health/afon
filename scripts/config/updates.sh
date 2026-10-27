if [ -z "$AFON_HOME" ]; then
  echo "AFON_HOME is not set"
  exit 1
fi

# The config dir is either a symlink $AFON_HOME/config -> config-<env>, as set by the env switch, or a standalone dir.
# Either way the files updated are those of the real dir, and the dir itself is never moved, renamed or relinked.
CONFIG_LINK="$AFON_HOME/config"
if [ ! -d "$CONFIG_LINK" ]; then
  echo "Missing config dir $CONFIG_LINK"
  exit 1
fi
CONFIG_DIR="$(cd "$CONFIG_LINK" && pwd -P)"
if [ -L "$CONFIG_LINK" ]; then
  echo "Config dir $CONFIG_LINK is a symlink to $CONFIG_DIR"
else
  echo "Config dir $CONFIG_DIR is standalone, not a symlink to a config env"
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
  "AZURE_CLIENT_SECRET=AZURE_APP_SECRET"
  "GCP_DOMAIN_NAME=GCP_POLARIS_DOMAIN_NAME"
  "GCP_EXTERNAL_BUCKET_NAME=GCP_BUCKET_NAME_EXTERNAL"
  "AWS_CATALOG_NAMES=AWS_CATALOG_IDS"
  "AZURE_CATALOG_NAMES=AZURE_CATALOG_IDS"
  "GCP_CATALOG_NAMES=GCP_CATALOG_IDS"
)

# Properties that are no longer used, to remove from every $CONFIG_DIR/*.properties file, with the comments that only
# introduced them.  They are ignored if left, so this is only to tidy up.
# Add an entry here whenever a property is removed.
REMOVALS=(
  # AWS looks up the hosted zones and certificates of the domain names, see docs/dns_config.md
  "AWS_KEYCLOAK_DNS_ZONE"
  "AWS_JUPYTER_DNS_ZONE"
  "AWS_POLARIS_DNS_ZONE"
  "AWS_KEYCLOAK_CERT_ARN"
  "AWS_JUPYTER_CERT_ARN"
  "AWS_POLARIS_CERT_ARN"
)
REMOVED_COMMENTS=(
  "# DNS zones"
  "# Cert ARNs"
)

shopt -s nullglob
FILES=("$CONFIG_DIR"/*.properties)
shopt -u nullglob
if [ ${#FILES[@]} -eq 0 ]; then
  echo "No *.properties files found in $CONFIG_DIR"
  exit 0
fi

# The real path of a file, ie that of its target if it is a symlink, eg to a file shared by the config envs.
real_path() {
  perl -MCwd=abs_path -e 'print abs_path(shift)' "$1"
}

echo "Renaming and removing properties in $CONFIG_DIR"
for LISTED in "${FILES[@]}"; do {
  # A file that is a symlink is updated through it, so that it stays one, rather than being replaced by a copy.
  FILE="$(real_path "$LISTED")"
  if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
    echo "$(basename "$LISTED"): not a file (dangling symlink?), skipped"
    continue
  fi
  NOTE=""
  [ "$FILE" != "$LISTED" ] && NOTE=" (symlink to $FILE)"

  # The edits are made to a copy, so that the file is only touched, in one step, if they change it, and a failed run leaves it as is.
  WORK="$(mktemp "$FILE.XXXXXX")" || { echo "$(basename "$LISTED"): cannot write beside $FILE, skipped"; continue; }
  cp -p "$FILE" "$WORK"
  for RENAME in "${RENAMES[@]}"; do {
    OLD_KEY="${RENAME%%=*}"
    NEW_KEY="${RENAME#*=}"
    # \b-anchored so eg <KEY> does not also match inside <KEY>_URL.
    perl -pi -e "s/\b\Q${OLD_KEY}\E\b/${NEW_KEY}/g" "$WORK"
  } done
  for KEY in "${REMOVALS[@]}"; do {
    perl -ni -e "print unless /^\\s*\Q${KEY}\E\\s*=/" "$WORK"
  } done
  for COMMENT in "${REMOVED_COMMENTS[@]}"; do {
    # only where nothing is left under the comment, ie the next line is blank or the end of the file
    perl -0pi -e "s/^\Q${COMMENT}\E\\n(?:\\n|\\z)//mg" "$WORK"
  } done

  if cmp -s "$FILE" "$WORK"; then
    rm "$WORK"
    echo "$(basename "$LISTED"): no properties to rename or remove$NOTE"
  else
    # never overwrites the backup of an earlier run, which may be the only copy of the original
    BACKUP="$FILE.bak"
    [ -e "$BACKUP" ] && BACKUP="$FILE.bak.$(date +%Y%m%d%H%M%S)"
    cp -p "$FILE" "$BACKUP"
    mv "$WORK" "$FILE"
    echo "$(basename "$LISTED"): updated (backup: $(basename "$BACKUP"))$NOTE"
  fi
} done

# Moves the domain names of the deprecated ALLOWED_DOMAINS_CSV of afon.properties to whitelist.yaml, as its common domains, so
# that the same names are allowed as before.  They can then be arranged per cloud and catalog, see templates/config/whitelist.yaml.
# An existing whitelist.yaml is never overwritten, and the property is only removed once its names are in the new file.
PROPS="$(real_path "$CONFIG_DIR/afon.properties")"
WHITELIST="$CONFIG_DIR/whitelist.yaml"
if [ -n "$PROPS" ] && [ -f "$PROPS" ]; then
  DOMAINS="$(perl -ne 'if (/^\s*ALLOWED_DOMAINS_CSV\s*=(.*)$/) { $v = $1 } END { $v =~ s/\r//g; print join("\n", grep { length } map { s/^\s+|\s+$//gr } split(/,/, $v)) }' "$PROPS")"
  if [ -z "$DOMAINS" ]; then
    echo "ALLOWED_DOMAINS_CSV: not set in afon.properties, nothing to move to whitelist.yaml"
  elif [ -e "$WHITELIST" ]; then
    echo "ALLOWED_DOMAINS_CSV: whitelist.yaml already exists, so it is not overwritten: add the names to it by hand, then remove the property"
  else
    {
      echo "# Generated by config-updates from ALLOWED_DOMAINS_CSV: these domains are allowed on any cloud."
      echo "# Arrange them per cloud and catalog as in templates/config/whitelist.yaml, which explains the format."
      echo "common:"
      echo "  domains:"
      while IFS= read -r DOMAIN; do {
        DOMAIN="${DOMAIN//\\/\\\\}"
        echo "    - \"${DOMAIN//\"/\\\"}\""
      } done <<< "$DOMAINS"
    } > "$WHITELIST"
    BACKUP="$PROPS.bak"
    [ -e "$BACKUP" ] && BACKUP="$PROPS.bak.$(date +%Y%m%d%H%M%S)"
    cp -p "$PROPS" "$BACKUP"
    # with the comment that only introduced it, as in the template, if it is still there
    perl -0pi -e 's/^# TRE allowed domains: see whitelist\.yaml\.[^\n]*\n# if whitelist\.yaml is missing\.\n(?=\s*ALLOWED_DOMAINS_CSV)//m; s/^[ \t]*ALLOWED_DOMAINS_CSV[ \t]*=[^\n]*\n?//mg' "$PROPS"
    echo "ALLOWED_DOMAINS_CSV: moved $(echo "$DOMAINS" | wc -l | tr -d ' ') domains to whitelist.yaml and removed the property (backup: $(basename "$BACKUP"))"
  fi
fi

echo "Done. Review the diffs (eg: diff \$FILE.bak \$FILE), then run ./afon.sh -a config-validate."
