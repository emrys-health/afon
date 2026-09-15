if [ -z "$AFON_HOME" ]; then
  echo "AFON_HOME is not set"
  exit 1
fi

CONFIG_DIR="$AFON_HOME/config"
if [ ! -d "$CONFIG_DIR" ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit 1
fi

# One-time migration of a pre-catalogs-refactor config dir into the current scheme.
#
# Before the refactor, the active catalog set came from a single global CATALOG_NAMES_CSV
# (defaulting, if blank/unset, to every built-in catalog - see DEFAULT_CATALOG_NAMES_CSV below,
# copied from the now-removed AfonCatalogs.DEFAULT_CATALOG_NAMES_CSV), and each catalog's bucket
# was a fixed AfonProperty in afon-<cloud>.properties (eg AWS_S3_BUCKET_NAME_EXTERNAL).
#
# This migrates that into <CLOUD>_CATALOG_NAMES plus per-id AfonCatalog properties
# (<CLOUD>_CATALOG_NAME_<id>/_TYPE_<id>/_BUCKET_NAME_<id>/_ADMIN_GRANTS_<id>) in
# catalogs-<cloud>.properties, for every cloud with catalogs configured.
#
# Safe to run more than once: a cloud already migrated (its catalogs-<cloud>.properties already
# defines <CLOUD>_CATALOG_NAMES) is left untouched. Every file this script actually changes is
# backed up first (as <file>.bak); the backup is removed again if nothing turned out to change.

DEFAULT_CATALOG_NAMES_CSV="aws,aws_forbidden,aws_trino,azure,azure_external,azure_forbidden,gcp,gcp_external,gcp_forbidden"

backup_of() {
  echo "$1.bak"
}

# Backs up $1 (if it exists) before this script starts editing it.
start_backup() {
  local FILE="$1"
  if [ -f "$FILE" ]; then
    cp -p "$FILE" "$(backup_of "$FILE")"
  fi
}

# Compares $1 against the backup taken by start_backup, dropping the backup again if nothing
# changed, else reporting that it was kept.
finish_backup() {
  local FILE="$1" BACKUP
  BACKUP="$(backup_of "$FILE")"
  if [ ! -f "$BACKUP" ]; then
    return
  fi
  if [ -f "$FILE" ] && cmp -s "$FILE" "$BACKUP"; then
    rm "$BACKUP"
  else
    echo "  backup kept: $(basename "$BACKUP")"
  fi
}

# Resolves $2 the way scripts/config/afon-source.sh would - bash `${VAR}` interpolation against
# afon.properties plus afon-$1.properties (pass "" for $1 to resolve a plain afon.properties
# value) - in a subshell, so nothing leaks into this process or its exported environment.
resolve_value() {
  local CLOUD="$1" KEY="$2"
  (
    [ -f "$CONFIG_DIR/afon.properties" ] && source "$CONFIG_DIR/afon.properties" 2>/dev/null
    [ -n "$CLOUD" ] && [ -f "$CONFIG_DIR/afon-$CLOUD.properties" ] && source "$CONFIG_DIR/afon-$CLOUD.properties" 2>/dev/null
    echo "${!KEY}"
  )
}

# Removes a property's line from a file in place, if present.
remove_prop() {
  local FILE="$1" KEY="$2"
  if [ -f "$FILE" ] && grep -q "^${KEY}=" "$FILE"; then
    sed -i.tmp "/^${KEY}=/d" "$FILE" && rm -f "$FILE.tmp"
  fi
}

# The pre-refactor AfonProperty each catalog id's bucket used to be configured under.
old_bucket_property_of() {
  case "$1" in
    aws)             echo "AWS_S3_BUCKET_NAME" ;;
    aws_trino)       echo "AWS_S3_BUCKET_NAME_TRINO" ;;
    aws_external)    echo "AWS_S3_BUCKET_NAME_EXTERNAL" ;;
    aws_forbidden)   echo "AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS" ;;
    azure)           echo "AZURE_BUCKET_NAME" ;;
    azure_external)  echo "AZURE_BUCKET_NAME_EXTERNAL" ;;
    azure_forbidden) echo "AZURE_BUCKET_NAME_FORBIDDEN" ;;
    gcp)             echo "GCP_BUCKET_NAME" ;;
    gcp_external)    echo "GCP_BUCKET_NAME_EXTERNAL" ;;
    gcp_forbidden)   echo "GCP_BUCKET_NAME_FORBIDDEN" ;;
  esac
}

type_of_id() {
  case "$1" in
    *_external) echo "external" ;;
    *)          echo "internal" ;;
  esac
}

admin_grants_of_id() {
  case "$1" in
    *_forbidden) echo "false" ;;
    *)           echo "true" ;;
  esac
}

# Every catalog id a pre-refactor config could possibly have had for a given cloud - the old
# AfonCatalog.Variant suffix parsing (BASE/FORBIDDEN/TRINO/EXTERNAL) had no way to define anything
# beyond these, so this list is exhaustive.
candidate_ids_of_cloud() {
  case "$1" in
    aws)   echo "aws aws_trino aws_external aws_forbidden" ;;
    azure) echo "azure azure_external azure_forbidden" ;;
    gcp)   echo "gcp gcp_external gcp_forbidden" ;;
  esac
}

csv_contains() {
  local ID="$1" CANDIDATE
  for CANDIDATE in "${CATALOG_IDS[@]}"; do
    CANDIDATE="$(echo "$CANDIDATE" | tr -d '[:space:]')"
    [ "$CANDIDATE" = "$ID" ] && return 0
  done
  return 1
}

CSV="$(resolve_value "" CATALOG_NAMES_CSV)"
if [ -z "$CSV" ]; then
  CSV="$DEFAULT_CATALOG_NAMES_CSV"
fi
IFS=',' read -ra CATALOG_IDS <<< "$CSV"

for CLOUD in aws azure gcp; do
  CLOUD_UPPER="$(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')"
  AFON_CLOUD_PROPS="$CONFIG_DIR/afon-$CLOUD.properties"
  CATALOGS_CLOUD_PROPS="$CONFIG_DIR/catalogs-$CLOUD.properties"

  if [ ! -f "$AFON_CLOUD_PROPS" ]; then
    echo "- $AFON_CLOUD_PROPS not found, skipping $CLOUD"
    continue
  fi

  # A candidate id is migrated if it was actually active pre-refactor (named in CATALOG_NAMES_CSV,
  # or in the old default set) OR its old bucket property already has a real value - eg AWS's
  # "external" catalog was never in the old default set (unlike Azure/GCP's), so an install that
  # configured AWS_S3_BUCKET_NAME_EXTERNAL without ever adding "aws_external" to CATALOG_NAMES_CSV
  # would otherwise silently lose that bucket.
  CLOUD_IDS=()
  for ID in $(candidate_ids_of_cloud "$CLOUD"); do
    VALUE="$(resolve_value "$CLOUD" "$(old_bucket_property_of "$ID")")"
    if csv_contains "$ID" || [ -n "$VALUE" ]; then
      CLOUD_IDS+=("$ID")
    fi
  done

  if [ ${#CLOUD_IDS[@]} -eq 0 ]; then
    echo "- No $CLOUD catalogs configured, skipping"
    continue
  fi

  if [ -f "$CATALOGS_CLOUD_PROPS" ] && grep -q "^${CLOUD_UPPER}_CATALOG_NAMES=" "$CATALOGS_CLOUD_PROPS"; then
    echo "- $CATALOGS_CLOUD_PROPS already has ${CLOUD_UPPER}_CATALOG_NAMES, skipping $CLOUD (already migrated)"
    continue
  fi

  NAMES_CSV="$(IFS=,; echo "${CLOUD_IDS[*]}")"
  echo "- Migrating $CLOUD catalogs: $NAMES_CSV"

  start_backup "$AFON_CLOUD_PROPS"
  CATALOGS_EXISTED_BEFORE=false
  [ -f "$CATALOGS_CLOUD_PROPS" ] && CATALOGS_EXISTED_BEFORE=true
  $CATALOGS_EXISTED_BEFORE && start_backup "$CATALOGS_CLOUD_PROPS"

  {
    echo "# See catalogs.properties for the format."
    echo "${CLOUD_UPPER}_CATALOG_NAMES=$NAMES_CSV"
    for ID in "${CLOUD_IDS[@]}"; do
      OLD_KEY="$(old_bucket_property_of "$ID")"
      VALUE="$(resolve_value "$CLOUD" "$OLD_KEY")"
      echo ""
      echo "${CLOUD_UPPER}_CATALOG_NAME_${ID}=${ID}"
      echo "${CLOUD_UPPER}_CATALOG_TYPE_${ID}=$(type_of_id "$ID")"
      echo "${CLOUD_UPPER}_BUCKET_NAME_${ID}=${VALUE}"
      echo "${CLOUD_UPPER}_ADMIN_GRANTS_${ID}=$(admin_grants_of_id "$ID")"
    done
  } >> "$CATALOGS_CLOUD_PROPS"

  for ID in "${CLOUD_IDS[@]}"; do
    remove_prop "$AFON_CLOUD_PROPS" "$(old_bucket_property_of "$ID")"
  done

  finish_backup "$AFON_CLOUD_PROPS"
  if $CATALOGS_EXISTED_BEFORE; then
    finish_backup "$CATALOGS_CLOUD_PROPS"
  else
    echo "  created $(basename "$CATALOGS_CLOUD_PROPS")"
  fi
done

# AWS_S3_BUCKET_NAME_FORBIDDEN (used only by AcceptanceTestConfig's own "aws_forbidden" test
# catalog - distinct from the "aws_forbidden" catalog id above) keeps its name but moves file,
# since it's no longer a typed AfonProperty entry.
AWS_PROPS="$CONFIG_DIR/afon-aws.properties"
CATALOGS_AWS_PROPS="$CONFIG_DIR/catalogs-aws.properties"
if [ -f "$AWS_PROPS" ] && grep -q "^AWS_S3_BUCKET_NAME_FORBIDDEN=" "$AWS_PROPS"; then
  VALUE="$(resolve_value aws AWS_S3_BUCKET_NAME_FORBIDDEN)"
  echo "- Moving AWS_S3_BUCKET_NAME_FORBIDDEN to catalogs-aws.properties"
  start_backup "$AWS_PROPS"
  start_backup "$CATALOGS_AWS_PROPS"
  {
    echo ""
    echo "# Used only by AcceptanceTestConfig for its own \"aws_forbidden\" test catalog - distinct from"
    echo "# AWS_BUCKET_NAME_aws_forbidden above so the two don't collide if both are configured in the"
    echo "# same test run."
    echo "AWS_S3_BUCKET_NAME_FORBIDDEN=${VALUE}"
  } >> "$CATALOGS_AWS_PROPS"
  remove_prop "$AWS_PROPS" AWS_S3_BUCKET_NAME_FORBIDDEN
  finish_backup "$AWS_PROPS"
  finish_backup "$CATALOGS_AWS_PROPS"
fi

AFON_PROPS="$CONFIG_DIR/afon.properties"
if [ -f "$AFON_PROPS" ] && grep -q "^CATALOG_NAMES_CSV=" "$AFON_PROPS"; then
  echo "- Removing obsolete CATALOG_NAMES_CSV from afon.properties"
  start_backup "$AFON_PROPS"
  remove_prop "$AFON_PROPS" CATALOG_NAMES_CSV
  finish_backup "$AFON_PROPS"
fi

if [ ! -f "$CONFIG_DIR/catalogs.properties" ] && [ -f "$AFON_ROOT/templates/config/catalogs.properties" ]; then
  echo "- Adding missing catalogs.properties"
  cp "$AFON_ROOT/templates/config/catalogs.properties" "$CONFIG_DIR/catalogs.properties"
fi

echo "Done. Review any *.bak files left behind (eg: diff \$FILE.bak \$FILE), then run ./afon.sh -a config-validate."
