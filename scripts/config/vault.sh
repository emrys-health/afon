#!/usr/bin/env bash
# Stores snapshots of the afon config files in the secret store of a cloud, and retrieves them by a tag:
# AWS Secrets Manager, Azure Key Vault or GCP Secret Manager.  The cloud is required, to be clear which store is used.
# For push only, the cloud can be all, which pushes the same config to the stores of all three clouds, each in turn
# (one failing does not stop the others, but the exit status is then 1).  Then --region, --vault and --project are not
# given: each cloud uses its config or defaults.
#
# config-vault {push|pull|delete <tag>|list} -c|--cloud {aws|azure|gcp|all} [-e|--env|--config-env <env>] [--region <region>] [--vault <name>] [--project <id>] [--force] [--dry-run]
#
# The env and the afon home are the global ones (--env, --config-env, AFON_ENV, AFON_CONFIG_ENV, --home, AFON_HOME),
# which have switched the config dir to <afon-home>/config-<env> before this runs.
# Without a global env, the env is the one that the <afon-home>/config symlink points to.  The files used are
# those of <afon-home>/config-<env>.
#
# The tag is required by push, pull and delete.  It is free-form text without spaces, eg a release, a git tag or a
# name, so that the snapshot is easy to retrieve by it.  As it is part of the name of the secret, the cloud limits
# its characters:
#   aws    letters, digits and / _ + = . @ -
#   azure  letters, digits and -
#   gcp    letters, digits and _ -
# On azure and gcp a dot or underscore in the tag is converted to a dash, for consistency (azure allows no underscores,
# nor dots, and gcp no dots), eg 1.0-beta-9 becomes 1-0-beta-9.  That is the tag that is stored, and the x.y.z, x_y_z
# and x-y-z forms can be given to push, pull and delete.
#
# 'push' stores all the config files of the config dir, empty ones included, as one secret, whose value is a JSON
# object of the file names to their base64-encoded contents.  base64 is used so that multi-line values
# (SNOWFLAKE_PRIVATE_KEY, GOOGLE_APPLICATION_CREDENTIALS) survive the round trip byte for byte.
# The secret is named and tagged as follows:
#   aws    afon/<env>/<tag>, with the tags afon:env and afon:tag
#   azure  afon-<env>-<tag>, with the tags afon-env and afon-tag
#   gcp    afon-<env>-<tag>, with the annotations afon-env and afon-tag
# A snapshot over the size limit of the cloud is gzipped and base64-encoded, and pull decompresses it.
# Pushing again with the same tag and env needs --force, which replaces the value of the snapshot (on Azure and GCP
# by adding a new version of the secret, which keeps the previous ones).
#
# 'pull' finds the snapshot of the env by its tag, compares its files with those of the config dir, and writes back
# those that differ or are missing.  Overwriting the files that differ needs --force.
#
# 'list' lists the snapshots of the env, and 'delete' removes the snapshot of the tag.  Deleted secrets are kept by
# AWS for a recovery window of 30 days, in which the same tag cannot be pushed again, unless deleted with --force.
# Azure soft-deletes them, so the same tag cannot be pushed again until the secret is purged, which --force does
# (needing the permission to purge).  GCP deletes them at once.
#
# AWS: the secrets are encrypted with the AWS managed key aws/secretsmanager.  Requires the aws CLI, logged in with
# permissions secretsmanager:CreateSecret, PutSecretValue, UpdateSecret, TagResource, ListSecrets, GetSecretValue
# and DeleteSecret, and jq.
# The region is --region, else the default of the aws CLI (AWS_REGION, AWS_DEFAULT_REGION or the profile).  It is
# not read from the afon config, as pull may have to run before there is any.
#
# Azure: the secrets are kept in the Key Vault --vault, else AZURE_KEY_VAULT_NAME of afon-azure.properties, which is
# created by 'azure-keyvault create'.  Requires the az CLI, logged in with the role Key Vault Secrets Officer (or
# Key Vault Secrets User to pull and list only) on the vault, and jq.  A Key Vault secret holds at most 25 KB.
#
# GCP: the secrets are kept in the project --project, else GCP_PROJECT_ID of afon-gcp.properties, whose Secret
# Manager API must be enabled.  Requires the gcloud CLI, logged in with the role Secret Manager Admin, and jq.
# A Secret Manager secret holds at most 64 KiB.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {push|pull|delete <tag>|list} [--region <region>] [--vault <name>] [--project <id>] [--force] [--dry-run]"
  echo "actions:  push   :  writes the config files to the snapshot secret of the tag.  Refuses to replace an existing"
  echo "                    snapshot of the same tag without --force"
  echo "          pull   :  writes the config files held in the snapshot of the tag back into the config dir, if they"
  echo "                    differ.  Refuses to overwrite the files that differ without --force"
  echo "          list   :  lists the snapshots of the env"
  echo "          delete :  removes the snapshot of the tag.  AWS keeps it for a recovery window of $RECOVERY_DAYS days, Azure"
  echo "                    soft-deletes it, GCP deletes it at once.  With --force it is removed at once (purged on Azure)"
  echo "cloud:      the cloud of the secret store: aws (Secrets Manager), azure (Key Vault) or gcp (Secret Manager)."
  echo "            It is the global one, given by -c or --cloud or AFON_CLOUD.  Required: there"
  echo "            is no default.  For push only it can also be all, to push to the stores of all clouds."
  echo "tag:        the tag of the snapshot, free-form text without spaces.  The characters allowed are those of the"
  echo "            secret names of the cloud: aws letters, digits and / _ + = . @ -, azure letters, digits and -,"
  echo "            gcp letters, digits and _ -.  Required by push, pull and delete."
  echo "region:     the AWS region.  Defaults to the one of the aws CLI.  Only for aws."
  echo "vault:      the Azure Key Vault.  Defaults to AZURE_KEY_VAULT_NAME of afon-azure.properties.  Only for azure."
  echo "project:    the GCP project.  Defaults to GCP_PROJECT_ID of afon-gcp.properties.  Only for gcp."
  echo "force:      the flag to let push replace a snapshot, pull overwrite the config files that differ, and"
  echo "            delete skip the recovery window."
  echo "dry-run:    the flag to report what would be done, without writing anything."
  echo "The env is the global one, given by --env or --config-env else the one that"
  echo "<afon-home>/config points to.  The config dir is <afon-home>/config-<env>."
}

NAME_PREFIX="afon"
RECOVERY_DAYS=30

# Sets what depends on the cloud: the CLI it needs, the name of its secrets and the limits on them.  The name of a
# secret is checked as a whole, as the tag is part of it.
set_cloud(){
  case "$VAULT_CLOUD" in
    aws)
      CLI=aws
      NAME_SEPARATOR="/"
      NAME_PATTERN='^[A-Za-z0-9/_+=.@-]+$'
      NAME_CHARACTERS="letters, digits and / _ + = . @ -"
      NAME_MAX_LENGTH=512
      # Secrets Manager limits the value of a secret to 64 KB.
      SNAPSHOT_MAX_BYTES=65536
      TAG_ENV="afon:env"
      TAG_TAG="afon:tag"
      ;;
    azure)
      CLI=az
      NAME_SEPARATOR="-"
      NAME_PATTERN='^[A-Za-z0-9-]+$'
      NAME_CHARACTERS="letters, digits and -"
      NAME_MAX_LENGTH=127
      # Key Vault limits the value of a secret to 25 KB.
      SNAPSHOT_MAX_BYTES=25600
      TAG_ENV="afon-env"
      TAG_TAG="afon-tag"
      ;;
    gcp)
      CLI=gcloud
      NAME_SEPARATOR="-"
      NAME_PATTERN='^[A-Za-z0-9_-]+$'
      NAME_CHARACTERS="letters, digits and _ -"
      NAME_MAX_LENGTH=255
      # Secret Manager limits the value of a secret to 64 KiB.
      SNAPSHOT_MAX_BYTES=65536
      TAG_ENV="afon-env"
      TAG_TAG="afon-tag"
      ;;
    *)
      echo "Unknown cloud $VAULT_CLOUD: it must be aws, azure or gcp" >&2
      exit 1 ;;
  esac
}

aws_sm(){
  aws secretsmanager ${REGION:+--region "$REGION"} "$@"
}

check_tools(){
  local TOOL
  for TOOL in $CLI jq ; do {
    if ! command -v "$TOOL" > /dev/null 2>&1; then
      echo "Requires $TOOL on the PATH" >&2
      exit 1
    fi
  } done
}

# Removes the work dir, which holds the config, and stops.
fail(){
  echo "$1" >&2
  rm -rf "${WORK_DIR:-}"
  exit 1
}

# Checks that TAG is given and valid.
check_tag(){
  if [ -z "$TAG" ]; then
    echo "Missing tag, required by $ACTION" >&2
    usage; exit 1
  fi
  # Azure and GCP do not allow dots in the name of a secret, nor does Azure allow underscores, so on both a tag like
  # 1.0-beta-9 is kept as 1-0-beta-9, whichever form (x.y.z, x_y_z or x-y-z) is given, to push, pull and delete it.
  local CONVERTED="$TAG"
  if [ "$VAULT_CLOUD" != "aws" ]; then
    CONVERTED="${TAG//[._]/-}"
  fi
  if [ "$CONVERTED" != "$TAG" ]; then
    echo "Using tag $CONVERTED (converted from $TAG)"
    TAG="$CONVERTED"
  else
    echo "Using tag $TAG"
  fi
  # The name of the secret is checked, as it is made of the env and the tag, and the tag is also a tag value.
  local NAME
  NAME=$(snapshot_name)
  if [[ ! "$NAME" =~ $NAME_PATTERN ]] || [ ${#NAME} -gt $NAME_MAX_LENGTH ]; then
    echo "Invalid tag $TAG for env $SNAPSHOT_ENV on $VAULT_CLOUD: the name of the secret $NAME must be at most $NAME_MAX_LENGTH" >&2
    echo "characters of $NAME_CHARACTERS" >&2
    exit 1
  fi
}

# The name of the secret.  On Azure and GCP the dots and underscores of the env become dashes too.
snapshot_name(){
  local ENV_PART="$SNAPSHOT_ENV"
  if [ "$VAULT_CLOUD" != "aws" ]; then
    ENV_PART="${SNAPSHOT_ENV//[._]/-}"
  fi
  echo "$NAME_PREFIX$NAME_SEPARATOR$ENV_PART$NAME_SEPARATOR$TAG"
}

# The config files present in the config dir.
snapshot_files(){
  local FILE
  for FILE in $CONFIG_FILES ; do {
    if [ -f "$CONFIG_DIR/$FILE" ]; then
      echo "$FILE"
    fi
  } done
}

# Writes the snapshot to stdout: a JSON object of the config file names to their contents in base64.
# The contents are piped to jq, rather than given as args, so that they do not show in the process list.
snapshot_json(){
  local FILE
  for FILE in $(snapshot_files) ; do {
    base64 < "$CONFIG_DIR/$FILE" | tr -d '\n' | jq -Rs --arg name "$FILE" '{($name): .}'
  } done | jq -s 'add // {}'
}

# What follows is the part that depends on the cloud.  A snapshot is identified by its SNAPSHOT_ID: the ARN on AWS,
# the name of the secret on Azure and GCP.

# Prints the snapshots of the env, as a JSON array of {Name, Id, Tag, LastChangedDate}.  On Azure and GCP the tag is
# normalised as TAG is, so that a snapshot pushed with another form of it, eg 1_0-beta-9, is still found.
list_snapshots(){
  case "$VAULT_CLOUD" in
    aws)
      # The name filter of ListSecrets matches by prefix, so the env is also checked on its tag.
      aws_sm list-secrets --filters Key=name,Values="$NAME_PREFIX/$SNAPSHOT_ENV/" --output json \
        | jq --arg env "$SNAPSHOT_ENV" --arg tenv "$TAG_ENV" --arg ttag "$TAG_TAG" '
          [.SecretList[]
            | (.Tags // [] | from_entries) as $tags
            | select($tags[$tenv] == $env)
            | {Name, Id: .ARN, Tag: ($tags[$ttag] // ""), LastChangedDate}]'
      ;;
    azure)
      az keyvault secret list --vault-name "$VAULT_NAME" --output json \
        | jq --arg env "$SNAPSHOT_ENV" --arg tenv "$TAG_ENV" --arg ttag "$TAG_TAG" '
          [.[]
            | (.tags // {}) as $tags
            | select($tags[$tenv] == $env)
            | {Name: .name, Id: .name, Tag: ($tags[$ttag] // "" | gsub("[._]"; "-")), LastChangedDate: .attributes.updated}]'
      ;;
    gcp)
      gcloud secrets list --project "$VAULT_PROJECT" --format=json \
        | jq --arg env "$SNAPSHOT_ENV" --arg tenv "$TAG_ENV" --arg ttag "$TAG_TAG" '
          [.[]
            | (.annotations // {}) as $tags
            | select($tags[$tenv] == $env)
            | (.name | split("/") | last) as $name
            | {Name: $name, Id: $name, Tag: ($tags[$ttag] // "" | gsub("[._]"; "-")), LastChangedDate: .createTime}]'
      ;;
  esac
}

# Prints the id of the snapshot of the env tagged with TAG, or nothing if there is none.
find_snapshot(){
  if [ "$VAULT_CLOUD" == "aws" ]; then
    # The tag filters of ListSecrets match any tag with the key and any with the value, by prefix, so the match is
    # made exactly here.
    aws_sm list-secrets --filters Key=tag-key,Values="$TAG_TAG" Key=tag-value,Values="$TAG" --output json \
      | jq -r --arg env "$SNAPSHOT_ENV" --arg tag "$TAG" --arg tenv "$TAG_ENV" --arg ttag "$TAG_TAG" '
        .SecretList[] | (.Tags // [] | from_entries) as $tags
          | select($tags[$tenv] == $env and $tags[$ttag] == $tag) | .ARN' \
      | head -1
  else
    list_snapshots | jq -r --arg tag "$TAG" '.[] | select(.Tag == $tag) | .Id' | head -1
  fi
}

# Prints the value of the snapshot of the given id.
read_snapshot(){
  case "$VAULT_CLOUD" in
    aws)
      aws_sm get-secret-value --secret-id "$1" --query SecretString --output text ;;
    azure)
      az keyvault secret show --vault-name "$VAULT_NAME" --name "$1" --query value --output tsv ;;
    gcp)
      gcloud secrets versions access latest --secret "$1" --project "$VAULT_PROJECT" ;;
  esac
}

# Creates the snapshot NAME, tagged for the env and TAG, with the value in the file.
create_snapshot(){
  local NAME="$1" VALUE_FILE="$2"
  case "$VAULT_CLOUD" in
    aws)
      aws_sm create-secret --name "$NAME" --description "$DESCRIPTION" \
        --secret-string "file://$VALUE_FILE" --tags "Key=$TAG_ENV,Value=$SNAPSHOT_ENV" "Key=$TAG_TAG,Value=$TAG" > /dev/null ;;
    azure)
      az keyvault secret set --vault-name "$VAULT_NAME" --name "$NAME" --file "$VALUE_FILE" \
        --description "$DESCRIPTION" --tags "$TAG_ENV=$SNAPSHOT_ENV" "$TAG_TAG=$TAG" --output none ;;
    gcp)
      gcloud secrets create "$NAME" --project "$VAULT_PROJECT" --replication-policy=automatic \
        --set-annotations="$TAG_ENV=$SNAPSHOT_ENV,$TAG_TAG=$TAG" > /dev/null \
        && gcloud secrets versions add "$NAME" --project "$VAULT_PROJECT" --data-file="$VALUE_FILE" > /dev/null ;;
  esac
}

# Replaces the value of the existing snapshot of the given id with the one in the file, and refreshes its tags.
replace_snapshot(){
  local ID="$1" VALUE_FILE="$2"
  case "$VAULT_CLOUD" in
    aws)
      aws_sm put-secret-value --secret-id "$ID" --secret-string "file://$VALUE_FILE" > /dev/null \
        && aws_sm update-secret --secret-id "$ID" --description "$DESCRIPTION" > /dev/null \
        && aws_sm tag-resource --secret-id "$ID" --tags "Key=$TAG_ENV,Value=$SNAPSHOT_ENV" "Key=$TAG_TAG,Value=$TAG" ;;
    azure)
      # Setting a secret adds a version of it.  The tags are those of the new version, so they are given again.
      az keyvault secret set --vault-name "$VAULT_NAME" --name "$ID" --file "$VALUE_FILE" \
        --description "$DESCRIPTION" --tags "$TAG_ENV=$SNAPSHOT_ENV" "$TAG_TAG=$TAG" --output none ;;
    gcp)
      gcloud secrets versions add "$ID" --project "$VAULT_PROJECT" --data-file="$VALUE_FILE" > /dev/null ;;
  esac
}

# Deletes the snapshot of the given id: at once if FORCE, else, where the cloud has one, with a recovery.
delete_snapshot(){
  local ID="$1"
  case "$VAULT_CLOUD" in
    aws)
      if [ "$FORCE" == "true" ]; then
        aws_sm delete-secret --secret-id "$ID" --force-delete-without-recovery > /dev/null
      else
        aws_sm delete-secret --secret-id "$ID" --recovery-window-in-days $RECOVERY_DAYS > /dev/null
      fi ;;
    azure)
      az keyvault secret delete --vault-name "$VAULT_NAME" --name "$ID" --output none || return 1
      if [ "$FORCE" == "true" ]; then
        # A soft-deleted secret may take a moment to be purgeable.
        local I
        for I in 1 2 3 4 5 6 ; do {
          az keyvault secret purge --vault-name "$VAULT_NAME" --name "$ID" 2>/dev/null && return 0
          sleep 5
        } done
        echo "$ID: deleted, but failed to purge it: the permission to purge may be missing" >&2
        return 1
      fi ;;
    gcp)
      gcloud secrets delete "$ID" --project "$VAULT_PROJECT" --quiet > /dev/null ;;
  esac
}

# Sets SNAPSHOT_ID to the snapshot of the env tagged with TAG, or stops if there is none.
require_snapshot(){
  SNAPSHOT_ID=$(find_snapshot)
  if [ -z "$SNAPSHOT_ID" ]; then
    echo "No snapshot of env $SNAPSHOT_ENV tagged $TAG" >&2
    exit 1
  fi
}

push(){
  if [ ! -d "$CONFIG_DIR" ]; then
    echo "Missing config dir $CONFIG_DIR" >&2
    exit 1
  fi
  local NAME COUNT
  NAME=$(snapshot_name)
  COUNT=$(snapshot_files | wc -l | tr -d ' ')
  DESCRIPTION="afon config $CONFIG_DIR of env $SNAPSHOT_ENV tagged $TAG"

  if [ "$DRY_RUN" == "true" ]; then
    echo "$NAME: would store $COUNT files from $CONFIG_DIR, tagged $TAG_ENV=$SNAPSHOT_ENV $TAG_TAG=$TAG"
    snapshot_files | sed 's/^/  /'
    return
  fi

  # The value is written to a file in a private work dir, rather than given as an arg, so that it does not
  # show in the process list.
  WORK_DIR=$(mktemp -d)
  chmod 700 "$WORK_DIR"
  local VALUE_FILE="$WORK_DIR/snapshot.json"
  snapshot_json > "$VALUE_FILE" || fail "Failed to read the config files"
  local SIZE
  SIZE=$(wc -c < "$VALUE_FILE" | tr -d ' ')
  if [ "$SIZE" -gt $SNAPSHOT_MAX_BYTES ]; then
    # Too big as it is: it is gzipped and base64-encoded, which pull recognises as the value is not a JSON object.
    gzip -9 -c < "$VALUE_FILE" | base64 | tr -d '\n' > "$VALUE_FILE.gz" || fail "Failed to compress the snapshot"
    mv "$VALUE_FILE.gz" "$VALUE_FILE"
    echo "The snapshot is $SIZE bytes, over the $SNAPSHOT_MAX_BYTES bytes $VAULT_CLOUD allows for a secret: compressed"
    SIZE=$(wc -c < "$VALUE_FILE" | tr -d ' ')
  fi
  if [ "$SIZE" -gt $SNAPSHOT_MAX_BYTES ]; then
    fail "The snapshot is $SIZE bytes, over the $SNAPSHOT_MAX_BYTES bytes $VAULT_CLOUD allows for a secret"
  fi

  local EXISTING
  EXISTING=$(find_snapshot)
  if [ -z "$EXISTING" ]; then
    create_snapshot "$NAME" "$VALUE_FILE" || fail "Failed to create $NAME"
  elif [ "$FORCE" != "true" ]; then
    fail "$NAME already exists.  Use --force to replace it, or delete it first."
  else
    replace_snapshot "$EXISTING" "$VALUE_FILE" || fail "Failed to replace $NAME"
  fi
  rm -rf "$WORK_DIR"
  echo "$NAME: stored $COUNT files from $CONFIG_DIR ($SIZE bytes), tagged $TAG_ENV=$SNAPSHOT_ENV $TAG_TAG=$TAG"
}

pull(){
  require_snapshot
  local SNAPSHOT
  SNAPSHOT=$(snapshot_name)
  local VALUE
  VALUE=$(read_snapshot "$SNAPSHOT_ID") \
    || { echo "Failed to read $SNAPSHOT" >&2; exit 1; }
  # A snapshot too big to store as it is was compressed by push: gzipped, then base64-encoded.
  if [[ "$VALUE" != "{"* ]]; then
    VALUE=$(printf '%s' "$VALUE" | base64 -d | gunzip) || { echo "Failed to decompress $SNAPSHOT" >&2; exit 1; }
  fi

  # The files are all decoded first, so that a bad snapshot leaves the config dir alone, and so that they can be
  # compared with those of the config dir.
  WORK_DIR=$(mktemp -d)
  chmod 700 "$WORK_DIR"
  local NAME
  for NAME in $(printf '%s' "$VALUE" | jq -r 'keys[]') ; do {
    # Only plain names are taken, whatever the snapshot holds.
    if [[ ! "$NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
      echo "$NAME: skipped, not a plain file name"
      continue
    fi
    printf '%s' "$VALUE" | jq -r --arg name "$NAME" '.[$name]' | base64 -d > "$WORK_DIR/$NAME" \
      || fail "Failed to decode $NAME of $SNAPSHOT"
  } done

  # Only the files that differ are written.  Overwriting a config file that differs from the snapshot needs --force,
  # whereas a file missing from the config dir is simply added.  Config files that the snapshot lacks are left alone.
  local CONFIG_FILE CHANGED=() ADDED=() UNCHANGED=0
  for CONFIG_FILE in "$WORK_DIR"/* ; do {
    [ -f "$CONFIG_FILE" ] || continue
    NAME=$(basename "$CONFIG_FILE")
    if [ ! -f "$CONFIG_DIR/$NAME" ]; then
      ADDED+=("$NAME")
    elif cmp -s "$CONFIG_FILE" "$CONFIG_DIR/$NAME"; then
      UNCHANGED=$((UNCHANGED + 1))
    else
      CHANGED+=("$NAME")
    fi
  } done
  for NAME in ${CHANGED[@]+"${CHANGED[@]}"} ; do echo "$NAME: differs from $SNAPSHOT" ; done
  for NAME in ${ADDED[@]+"${ADDED[@]}"} ; do echo "$NAME: missing from $CONFIG_DIR" ; done
  echo "$UNCHANGED files unchanged, ${#CHANGED[@]} changed and ${#ADDED[@]} missing in $CONFIG_DIR"

  if [ ${#CHANGED[@]} -eq 0 ] && [ ${#ADDED[@]} -eq 0 ]; then
    rm -rf "$WORK_DIR"
    echo "Config dir $CONFIG_DIR is up to date with $SNAPSHOT"
    return
  fi
  if [ "$DRY_RUN" == "true" ]; then
    rm -rf "$WORK_DIR"
    echo "$SNAPSHOT: would write $((${#CHANGED[@]} + ${#ADDED[@]})) files to $CONFIG_DIR$([ ${#CHANGED[@]} -gt 0 ] && [ "$FORCE" != "true" ] && echo ", with --force")"
    return
  fi
  if [ ${#CHANGED[@]} -gt 0 ] && [ "$FORCE" != "true" ]; then
    fail "Config dir $CONFIG_DIR has ${#CHANGED[@]} files that differ from $SNAPSHOT.  Use --force to overwrite them."
  fi

  mkdir -p "$CONFIG_DIR"
  for NAME in ${CHANGED[@]+"${CHANGED[@]}"} ${ADDED[@]+"${ADDED[@]}"} ; do {
    cp "$WORK_DIR/$NAME" "$CONFIG_DIR/$NAME"
    chmod 600 "$CONFIG_DIR/$NAME"
    echo "$NAME: written to $CONFIG_DIR"
  } done
  rm -rf "$WORK_DIR"
  echo "Pulled $((${#CHANGED[@]} + ${#ADDED[@]})) config files from $SNAPSHOT to $CONFIG_DIR"
}

list(){
  echo "Snapshots of env $SNAPSHOT_ENV"
  list_snapshots | jq -r 'sort_by(.LastChangedDate) | reverse | .[]
    | "\(.Name)\ttag \(.Tag)\tchanged \(.LastChangedDate)"'
}

delete(){
  require_snapshot
  local SNAPSHOT
  SNAPSHOT=$(snapshot_name)
  local OUTCOME
  case "$VAULT_CLOUD" in
    aws)
      if [ "$FORCE" == "true" ]; then OUTCOME="at once"; else OUTCOME="with a recovery window of $RECOVERY_DAYS days"; fi ;;
    azure)
      if [ "$FORCE" == "true" ]; then OUTCOME="and purged at once"; else OUTCOME="soft-deleted, until purged or retained for the vault's retention days"; fi ;;
    gcp)
      OUTCOME="at once" ;;
  esac
  if [ "$DRY_RUN" == "true" ]; then
    echo "$SNAPSHOT: would delete, $OUTCOME"
    return
  fi
  delete_snapshot "$SNAPSHOT_ID" || { echo "Failed to delete $SNAPSHOT" >&2; exit 1; }
  case "$VAULT_CLOUD" in
    aws)
      if [ "$FORCE" == "true" ]; then
        echo "$SNAPSHOT: deleted"
      else
        echo "$SNAPSHOT: deleted, recoverable for $RECOVERY_DAYS days with 'aws secretsmanager restore-secret --secret-id $SNAPSHOT'"
      fi ;;
    azure)
      if [ "$FORCE" == "true" ]; then
        echo "$SNAPSHOT: deleted and purged"
      else
        echo "$SNAPSHOT: soft-deleted, recoverable with 'az keyvault secret recover --vault-name $VAULT_NAME --name $SNAPSHOT_ID'; it can be pushed again once purged"
      fi ;;
    gcp)
      echo "$SNAPSHOT: deleted" ;;
  esac
}

ACTION="${1:-}"
shift || true

TAG=
# The cloud is the global one (-c|--cloud, AFON_CLOUD), given to the script as CLOUD.
# It is required, so that it is always clear which secret store is used.  all is accepted by push only.
VAULT_CLOUD=$(echo "${CLOUD:-}" | tr '[:upper:]' '[:lower:]')
REGION=
VAULT_NAME=
VAULT_PROJECT=
FORCE=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      REGION="$2"; shift 2 ;;
    --vault)
      VAULT_NAME="$2"; shift 2 ;;
    --project)
      VAULT_PROJECT="$2"; shift 2 ;;
    --force)
      FORCE=true; shift ;;
    --dry-run)
      DRY_RUN=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    --)
      # Passed on by afon-cli.sh, which, unlike afon.sh, does not need it.
      shift ;;
    -*)
      echo "Unknown option: $1" >&2; usage; exit 1 ;;
    *)
      if [ -n "$TAG" ] || [ "$ACTION" == "list" ]; then
        echo "Unexpected arg: $1" >&2; usage; exit 1
      fi
      TAG="$1"; shift ;;
  esac
done

# AFON_HOME and CONFIG_ENV are set by afon.sh and afon-cli.sh, which have already switched the config symlink to
# config-<env> when given an env.
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
SNAPSHOT_ENV="${CONFIG_ENV:-}"
if [ -z "$SNAPSHOT_ENV" ] && [ -L "$AFON_HOME/config" ]; then
  SNAPSHOT_ENV=$(basename "$(readlink "$AFON_HOME/config")")
  if [[ "$SNAPSHOT_ENV" == config-* ]]; then
    SNAPSHOT_ENV="${SNAPSHOT_ENV#config-}"
  else
    SNAPSHOT_ENV=
  fi
fi
if [ -z "$SNAPSHOT_ENV" ] || [[ ! "$SNAPSHOT_ENV" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "No config env: specify --env or --config-env or switch to one with config-switch" >&2
  exit 1
fi
CONFIG_DIR="$AFON_HOME/config-$SNAPSHOT_ENV"
echo "Using env $SNAPSHOT_ENV"
echo "Using config dir $CONFIG_DIR"

if [ -z "$VAULT_CLOUD" ]; then
  echo "Missing cloud, required by config-vault: specify -c|--cloud {aws|azure|gcp|all} or set AFON_CLOUD" >&2
  usage; exit 1
fi
if [ "$VAULT_CLOUD" == "all" ] && [ "$ACTION" != "push" ]; then
  echo "Cloud all is for push only: $ACTION needs one of aws, azure or gcp" >&2
  usage; exit 1
fi
if [ "$VAULT_CLOUD" == "all" ] && { [ -n "$REGION" ] || [ -n "$VAULT_NAME" ] || [ -n "$VAULT_PROJECT" ]; }; then
  echo "--region, --vault and --project are for one cloud: with cloud all, each cloud uses its config or defaults" >&2
  exit 1
fi

# Runs the action on the cloud VAULT_CLOUD.  It is run in a subshell, as it stops with exit, so that with cloud all a
# failure on one cloud does not keep the others from being pushed.
run_on_cloud(){
  set_cloud
  echo "Using cloud $VAULT_CLOUD"
  if [ -n "$REGION" ] && [ "$VAULT_CLOUD" != "aws" ]; then
    echo "--region is for aws only" >&2; exit 1
  fi
  if [ -n "$VAULT_NAME" ] && [ "$VAULT_CLOUD" != "azure" ]; then
    echo "--vault is for azure only" >&2; exit 1
  fi
  if [ -n "$VAULT_PROJECT" ] && [ "$VAULT_CLOUD" != "gcp" ]; then
    echo "--project is for gcp only" >&2; exit 1
  fi
  if [ -n "$REGION" ] ; then
    echo "Using region $REGION"
  fi

  # Prints the value of a property of the config dir of the env, which may be set in terms of others, eg
  # ${ENV_NAME}-afon-key-vault.  The files are read from the config dir of the env rather than via the config symlink,
  # which may not exist yet, eg to pull.  Prints nothing if there is none.
  config_property(){
    local PROPERTY="$1" CLOUD_NAME="$2" FILE
    (
      set +eu
      set -a
      for FILE in afon.properties "afon-$CLOUD_NAME.properties" ; do {
        [ -f "$CONFIG_DIR/$FILE" ] && source "$CONFIG_DIR/$FILE" > /dev/null 2>&1
      } done
      printf '%s' "${!PROPERTY:-}"
    )
  }

  # The Azure Key Vault and the GCP project are --vault and --project, else read from the config of the cloud.
  if [ "$VAULT_CLOUD" == "azure" ] && [ -z "$VAULT_NAME" ]; then
    VAULT_NAME=$(config_property AZURE_KEY_VAULT_NAME azure)
    if [ -z "$VAULT_NAME" ]; then
      # No config, eg to pull into a new env: the name that the templates give it.
      VAULT_NAME="$SNAPSHOT_ENV-afon-key-vault"
      echo "No AZURE_KEY_VAULT_NAME in the config of $CONFIG_DIR: using the default $VAULT_NAME, or give --vault"
    fi
  fi
  if [ "$VAULT_CLOUD" == "gcp" ] && [ -z "$VAULT_PROJECT" ]; then
    VAULT_PROJECT=$(config_property GCP_PROJECT_ID gcp)
    if [ -z "$VAULT_PROJECT" ] && command -v gcloud > /dev/null 2>&1; then
      # No config, eg to pull into a new env: the project of the gcloud CLI.
      VAULT_PROJECT=$(gcloud config get-value project 2>/dev/null || true)
      [ -n "$VAULT_PROJECT" ] && echo "No GCP_PROJECT_ID in the config of $CONFIG_DIR: using the project of gcloud, or give --project"
    fi
    if [ -z "$VAULT_PROJECT" ]; then
      echo "No GCP project: give --project, set GCP_PROJECT_ID in afon-gcp.properties of $CONFIG_DIR, or set one in gcloud" >&2
      exit 1
    fi
  fi
  case "$VAULT_CLOUD" in
    azure) echo "Using Key Vault $VAULT_NAME" ;;
    gcp)   echo "Using project $VAULT_PROJECT" ;;
  esac

  # All clouds are always stored, so that the config dir is restored whole.
  CLOUD=
  source $AFON_ROOT/scripts/config/config-files.sh

  case "$ACTION" in
    push|pull|delete)
      check_tools
      check_tag
      $ACTION
      ;;
    list)
      check_tools
      list
      ;;
    *)
      usage; exit 1 ;;
  esac
}

if [ "$VAULT_CLOUD" == "all" ]; then
  FAILED=()
  for VAULT_CLOUD in aws azure gcp ; do {
    echo "== Pushing to $VAULT_CLOUD"
    ( run_on_cloud ) || FAILED+=("$VAULT_CLOUD")
  } done
  if [ ${#FAILED[@]} -gt 0 ]; then
    echo "Failed to push to: ${FAILED[*]}" >&2
    exit 1
  fi
else
  run_on_cloud
fi
