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
# This migrates that into <CLOUD>_CATALOG_IDS plus per-id AfonCatalog properties
# (<CLOUD>_CATALOG_NAME_<id>/_TYPE_<id>/_BUCKET_NAME_<id>/_ADMIN_GRANTS_<id>) in
# catalogs-<cloud>.properties, for every cloud with catalogs configured. Also migrates AWS's
# Glue-backed catalog (the now-retired AWS_GLUE_CATALOG_NAME, never part of CATALOG_NAMES_CSV) into
# an "aws_glue" catalog id, if one was configured.
#
# Safe to run more than once: a cloud already migrated (its catalogs-<cloud>.properties already
# defines <CLOUD>_CATALOG_IDS) is left untouched. Every file this script actually changes is
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
    # No nounset here: a property that is not set at all (eg CATALOG_NAMES_CSV, which most configs never
    # defined) must resolve to "", not abort the script ("!KEY: unbound variable" under `set -u`).
    set +u
    [ -f "$CONFIG_DIR/afon.properties" ] && source "$CONFIG_DIR/afon.properties" 2>/dev/null
    [ -n "$CLOUD" ] && [ -f "$CONFIG_DIR/afon-$CLOUD.properties" ] && source "$CONFIG_DIR/afon-$CLOUD.properties" 2>/dev/null
    echo "${!KEY}"
  )
}

# Reads a property's literal value straight out of a file (last definition wins), or "" if absent
# or the file is missing. Unlike resolve_value, does no `${VAR}` interpolation - only safe to use
# on catalogs-<cloud>.properties after this script has already written it with resolved values.
get_prop() {
  local FILE="$1" KEY="$2"
  [ -f "$FILE" ] || return 0
  sed -n "s/^${KEY}=//p" "$FILE" | tail -1
}

# Removes a property's line from a file in place, if present.
remove_prop() {
  local FILE="$1" KEY="$2"
  if [ -f "$FILE" ] && grep -q "^${KEY}=" "$FILE"; then
    sed -i.tmp "/^${KEY}=/d" "$FILE" && rm -f "$FILE.tmp"
  fi
}

# The pre-refactor AfonProperty each catalog id's bucket used to be configured under. aws_glue
# never had its own bucket property - GlueAwsPolarisCatalog always hardcoded a reuse of
# aws_external's bucket - so it shares AWS_S3_BUCKET_NAME_EXTERNAL with aws_external here too.
old_bucket_property_of() {
  case "$1" in
    aws)             echo "AWS_S3_BUCKET_NAME" ;;
    aws_trino)       echo "AWS_S3_BUCKET_NAME_TRINO" ;;
    aws_external)    echo "AWS_S3_BUCKET_NAME_EXTERNAL" ;;
    aws_forbidden)   echo "AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS" ;;
    aws_glue)        echo "AWS_S3_BUCKET_NAME_EXTERNAL" ;;
    azure)           echo "AZURE_BUCKET_NAME" ;;
    azure_external)  echo "AZURE_BUCKET_NAME_EXTERNAL" ;;
    azure_forbidden) echo "AZURE_BUCKET_NAME_FORBIDDEN" ;;
    gcp)             echo "GCP_BUCKET_NAME" ;;
    gcp_external)    echo "GCP_BUCKET_NAME_EXTERNAL" ;;
    gcp_forbidden)   echo "GCP_BUCKET_NAME_FORBIDDEN" ;;
  esac
}

# The pre-refactor property whose non-blank value signals "this catalog was actually configured" -
# same as its bucket property for every id except aws_glue, which reused aws_external's bucket
# regardless of whether Glue itself was ever set up, so its own signal is the (also now-retired)
# AWS_GLUE_CATALOG_NAME instead.
signal_property_of() {
  case "$1" in
    aws_glue) echo "AWS_GLUE_CATALOG_NAME" ;;
    *)        old_bucket_property_of "$1" ;;
  esac
}

# The pre-refactor property holding this catalog's real/display name - unset (ie use the id itself,
# matching every other catalog) except aws_glue, which kept a separately-configured, already-
# registered Polaris catalog name that migration must preserve rather than renaming to "aws_glue".
old_name_property_of() {
  case "$1" in
    aws_glue) echo "AWS_GLUE_CATALOG_NAME" ;;
  esac
}

type_of_id() {
  case "$1" in
    aws_glue)   echo "glue" ;;
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
# beyond these, plus aws_glue (always a separate, manually-wired special case, never part of
# CATALOG_NAMES_CSV) - so this list is exhaustive.
candidate_ids_of_cloud() {
  case "$1" in
    aws)   echo "aws aws_trino aws_external aws_forbidden aws_glue" ;;
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
  # or in the old default set) OR its own signal property already has a real value - eg AWS's
  # "external" catalog was never in the old default set (unlike Azure/GCP's), so an install that
  # configured AWS_S3_BUCKET_NAME_EXTERNAL without ever adding "aws_external" to CATALOG_NAMES_CSV
  # would otherwise silently lose that bucket. aws_glue was never in CATALOG_NAMES_CSV at all, so
  # its signal property (AWS_GLUE_CATALOG_NAME) is the only thing that can activate it.
  CLOUD_IDS=()
  for ID in $(candidate_ids_of_cloud "$CLOUD"); do
    VALUE="$(resolve_value "$CLOUD" "$(signal_property_of "$ID")")"
    if csv_contains "$ID" || [ -n "$VALUE" ]; then
      CLOUD_IDS+=("$ID")
    fi
  done

  if [ ${#CLOUD_IDS[@]} -eq 0 ]; then
    echo "- No $CLOUD catalogs configured, skipping"
    continue
  fi

  # _CATALOG_NAMES is what <CLOUD>_CATALOG_IDS was briefly called (see updates.sh) - a config already
  # migrated under that name must not be migrated again.
  if [ -f "$CATALOGS_CLOUD_PROPS" ] && grep -qE "^${CLOUD_UPPER}_CATALOG_(IDS|NAMES)=" "$CATALOGS_CLOUD_PROPS"; then
    echo "- $CATALOGS_CLOUD_PROPS already defines the $CLOUD catalog ids, skipping $CLOUD (already migrated)"
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
    echo "${CLOUD_UPPER}_CATALOG_IDS=$NAMES_CSV"
    for ID in "${CLOUD_IDS[@]}"; do
      VALUE="$(resolve_value "$CLOUD" "$(old_bucket_property_of "$ID")")"
      NAME_KEY="$(old_name_property_of "$ID")"
      if [ -n "$NAME_KEY" ]; then
        NAME="$(resolve_value "$CLOUD" "$NAME_KEY")"
      else
        NAME="$ID"
      fi
      echo ""
      echo "${CLOUD_UPPER}_CATALOG_NAME_${ID}=${NAME}"
      echo "${CLOUD_UPPER}_CATALOG_TYPE_${ID}=$(type_of_id "$ID")"
      echo "${CLOUD_UPPER}_BUCKET_NAME_${ID}=${VALUE}"
      echo "${CLOUD_UPPER}_ADMIN_GRANTS_${ID}=$(admin_grants_of_id "$ID")"
    done
  } >> "$CATALOGS_CLOUD_PROPS"

  for ID in "${CLOUD_IDS[@]}"; do
    remove_prop "$AFON_CLOUD_PROPS" "$(old_bucket_property_of "$ID")"
    NAME_KEY="$(old_name_property_of "$ID")"
    [ -n "$NAME_KEY" ] && remove_prop "$AFON_CLOUD_PROPS" "$NAME_KEY"
  done

  finish_backup "$AFON_CLOUD_PROPS"
  if $CATALOGS_EXISTED_BEFORE; then
    finish_backup "$CATALOGS_CLOUD_PROPS"
  else
    echo "  created $(basename "$CATALOGS_CLOUD_PROPS")"
  fi
done

# AWS_S3_BUCKET_NAME_FORBIDDEN (AcceptanceTestConfig's own "aws_forbidden" test catalog) is retired
# entirely: AcceptanceTestConfig now just reuses afonCatalogs.awsForbidden()'s own bucket
# (AWS_BUCKET_NAME_aws_forbidden in catalogs-aws.properties, already migrated above), protected by
# an explicit IAM Deny statement rather than by being a separate, never-whitelisted bucket - so this
# is just dropped here rather than moved anywhere.
AWS_PROPS="$CONFIG_DIR/afon-aws.properties"
if [ -f "$AWS_PROPS" ] && grep -q "^AWS_S3_BUCKET_NAME_FORBIDDEN=" "$AWS_PROPS"; then
  OLD_VALUE="$(resolve_value aws AWS_S3_BUCKET_NAME_FORBIDDEN)"
  NEW_VALUE="$(get_prop "$CONFIG_DIR/catalogs-aws.properties" AWS_BUCKET_NAME_aws_forbidden)"
  echo "- Removing obsolete AWS_S3_BUCKET_NAME_FORBIDDEN from afon-aws.properties"
  if [ -n "$OLD_VALUE" ] && [ "$OLD_VALUE" != "$NEW_VALUE" ]; then
    echo "  WARNING: its value ('$OLD_VALUE') differs from aws_forbidden's own bucket"
    echo "  ('$NEW_VALUE') - the acceptance tests will now use the latter. Either update the IAM"
    echo "  Deny statement to target it, or point AWS_BUCKET_NAME_aws_forbidden at '$OLD_VALUE'"
    echo "  instead if you'd rather keep using that bucket."
  fi
  start_backup "$AWS_PROPS"
  remove_prop "$AWS_PROPS" AWS_S3_BUCKET_NAME_FORBIDDEN
  finish_backup "$AWS_PROPS"
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
