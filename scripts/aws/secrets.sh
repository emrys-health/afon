#!/usr/bin/env bash
# Stores snapshots of the afon config files in AWS Secrets Manager under a tag, and retrieves them by it.
#
# ./afon.sh -a aws-secrets [--] {push|pull|list|delete} [--home <afon-home>] [--env <env>] [--tag <tag>] [--region <region>] [--force] [--dry-run]
# (the -- is needed by afon.sh before any arg starting with -, eg: ./afon.sh -a aws-secrets -- push --env dev)
#
# Without an env, the files in <afon-home>/config are used, and the snapshots are filed under env 'default'.
# With an env, the files in <afon-home>/config-<env> are used.  The env is also taken from the global --config-env,
# --env or AFON_ENV of afon.sh, if --env is not given here.
#
# The tag is free-form text without spaces, eg a release, a git tag or a name, and defaults to the git commit hash
# of the current branch of the afon checkout.  AWS only allows letters, digits and / _ + = . @ - in it, as it is part
# of the name of the secret.
#
# 'push' stores all the config files of the config dir, empty ones included, as one secret named afon/<env>/<tag>,
# whose value is a JSON object of the file names to their base64-encoded contents.  base64 is used so that
# multi-line values (SNOWFLAKE_PRIVATE_KEY, GOOGLE_APPLICATION_CREDENTIALS) survive the round trip byte for byte.
# The secret is tagged with:
#   afon:env         the env
#   afon:tag         the tag
#   afon:git-commit  the git commit hash of the current branch of the afon checkout, when pushed
# Pushing again with the same tag and env needs --force, which replaces the value of the snapshot.
#
# 'pull' finds the snapshot of the env by its tag, and writes the files back to the config dir.
#
# 'list' lists the snapshots of the env, and 'delete' removes the snapshot of the tag.  Deleted secrets are kept by
# AWS for a recovery window of 30 days, in which the same tag cannot be pushed again, unless deleted with --force.
#
# The secrets are encrypted with the AWS managed key aws/secretsmanager.  Requires the aws CLI, logged in with
# permissions secretsmanager:CreateSecret, PutSecretValue, UpdateSecret, TagResource, ListSecrets, GetSecretValue
# and DeleteSecret, git and jq.
# The region is --region, else the default of the aws CLI (AWS_REGION, AWS_DEFAULT_REGION or the profile).  It is
# not read from the afon config, as pull may have to run before there is any.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {push|pull|list|delete} [--home <afon-home>] [--env <env>] [--tag <tag>] [--region <region>] [--force] [--dry-run]"
  echo "actions:  push   :  writes the config files to the snapshot secret of the tag.  Refuses to replace an existing"
  echo "                    snapshot of the same tag without --force"
  echo "          pull   :  writes the config files held in the snapshot of the tag back into the config dir.  Refuses"
  echo "                    to touch a non-empty config dir without --force"
  echo "          list   :  lists the snapshots of the env"
  echo "          delete :  removes the snapshot of the tag, with a recovery window of $RECOVERY_DAYS days, or at once with --force"
  echo "home:       the afon home dir.  Defaults to \$HOME/.afon."
  echo "env:        the config env.  Reads or writes <afon-home>/config-<env>.  Defaults to the global config env, if"
  echo "            set, else to <afon-home>/config, with snapshots filed under env '$DEFAULT_ENV'."
  echo "tag:        the tag of the snapshot, free-form text without spaces, of letters, digits and / _ + = . @ -"
  echo "            Defaults to the git commit hash of the current branch of the afon checkout."
  echo "region:     the AWS region.  Defaults to the one of the aws CLI."
  echo "force:      the flag to let push replace a snapshot, pull overwrite the files of a non-empty config dir, and"
  echo "            delete skip the recovery window."
  echo "dry-run:    the flag to report what would be done, without writing anything."
}

DEFAULT_ENV="default"
NAME_PREFIX="afon"
TAG_ENV="afon:env"
TAG_TAG="afon:tag"
TAG_COMMIT="afon:git-commit"
# The characters AWS allows in a secret name, which also go in a tag value.  A tag value is at most 256 characters.
TAG_PATTERN='^[A-Za-z0-9/_+=.@-]+$'
TAG_MAX_LENGTH=256
# Secrets Manager limits the value of a secret to 64 KB.
SNAPSHOT_MAX_BYTES=65536
RECOVERY_DAYS=30

aws_sm(){
  aws secretsmanager ${REGION:+--region "$REGION"} "$@"
}

check_tools(){
  local TOOL
  for TOOL in aws git jq ; do {
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

# Prints the git commit hash of the current branch of the afon checkout.
current_commit(){
  git -C "$AFON_ROOT" rev-parse --verify --quiet HEAD \
    || { echo "No git commit in the afon checkout $AFON_ROOT, give a --tag" >&2; exit 1; }
}

# Sets TAG to the commit hash of the current branch, if not given, and checks it.
resolve_tag(){
  if [ -z "$TAG" ]; then
    TAG=$(current_commit)
    echo "Using tag $TAG, the git commit of the current branch"
  else
    echo "Using tag $TAG"
  fi
  if [[ ! "$TAG" =~ $TAG_PATTERN ]] || [ ${#TAG} -gt $TAG_MAX_LENGTH ]; then
    echo "Invalid tag $TAG: it must be at most $TAG_MAX_LENGTH letters, digits and / _ + = . @ -" >&2
    exit 1
  fi
}

snapshot_name(){
  echo "$NAME_PREFIX/$SNAPSHOT_ENV/$TAG"
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

# Prints the snapshots of the env, as a JSON array of {Name, ARN, Tag, Commit, LastChangedDate}.  The name
# filter of ListSecrets matches by prefix, so the env is also checked on its tag.
list_snapshots(){
  aws_sm list-secrets --filters Key=name,Values="$NAME_PREFIX/$SNAPSHOT_ENV/" --output json \
    | jq --arg env "$SNAPSHOT_ENV" --arg tenv "$TAG_ENV" --arg ttag "$TAG_TAG" --arg tcommit "$TAG_COMMIT" '
      [.SecretList[]
        | (.Tags // [] | from_entries) as $tags
        | select($tags[$tenv] == $env)
        | {Name, ARN, Tag: ($tags[$ttag] // ""), Commit: ($tags[$tcommit] // ""), LastChangedDate}]'
}

# Prints the ARN of the snapshot of the env tagged with TAG, or nothing if there is none.  The tag filters of
# ListSecrets match any tag with the key and any with the value, by prefix, so the match is made exactly here.
find_snapshot(){
  aws_sm list-secrets --filters Key=tag-key,Values="$TAG_TAG" Key=tag-value,Values="$TAG" --output json \
    | jq -r --arg env "$SNAPSHOT_ENV" --arg tag "$TAG" --arg tenv "$TAG_ENV" --arg ttag "$TAG_TAG" '
      .SecretList[] | (.Tags // [] | from_entries) as $tags
        | select($tags[$tenv] == $env and $tags[$ttag] == $tag) | .ARN' \
    | head -1
}

# Sets SNAPSHOT_ARN to the snapshot of the env tagged with TAG, or stops if there is none.
require_snapshot(){
  SNAPSHOT_ARN=$(find_snapshot)
  if [ -z "$SNAPSHOT_ARN" ]; then
    echo "No snapshot of env $SNAPSHOT_ENV tagged $TAG" >&2
    exit 1
  fi
}

push(){
  if [ ! -d "$CONFIG_DIR" ]; then
    echo "Missing config dir $CONFIG_DIR" >&2
    exit 1
  fi
  local COMMIT NAME COUNT
  # The commit is only recorded, so a checkout without one still pushes under a given tag.
  COMMIT=$(current_commit 2> /dev/null || true)
  NAME=$(snapshot_name)
  COUNT=$(snapshot_files | wc -l | tr -d ' ')
  local TAGS=("Key=$TAG_ENV,Value=$SNAPSHOT_ENV" "Key=$TAG_TAG,Value=$TAG")
  if [ -n "$COMMIT" ]; then
    TAGS+=("Key=$TAG_COMMIT,Value=$COMMIT")
  fi
  local DESCRIPTION="afon config $CONFIG_DIR of env $SNAPSHOT_ENV tagged $TAG${COMMIT:+, pushed at commit $COMMIT}"

  if [ "$DRY_RUN" == "true" ]; then
    echo "$NAME: would store $COUNT files from $CONFIG_DIR, tagged $TAG_ENV=$SNAPSHOT_ENV $TAG_TAG=$TAG${COMMIT:+ $TAG_COMMIT=$COMMIT}"
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
    fail "The snapshot is $SIZE bytes, over the $SNAPSHOT_MAX_BYTES bytes AWS allows for a secret"
  fi

  local EXISTING
  EXISTING=$(find_snapshot)
  if [ -z "$EXISTING" ]; then
    aws_sm create-secret --name "$NAME" --description "$DESCRIPTION" \
      --secret-string "file://$VALUE_FILE" --tags "${TAGS[@]}" > /dev/null \
      || fail "Failed to create $NAME"
  elif [ "$FORCE" != "true" ]; then
    fail "$NAME already exists.  Use --force to replace it, or delete it first."
  else
    aws_sm put-secret-value --secret-id "$EXISTING" --secret-string "file://$VALUE_FILE" > /dev/null \
      || fail "Failed to replace $NAME"
    aws_sm update-secret --secret-id "$EXISTING" --description "$DESCRIPTION" > /dev/null
    aws_sm tag-resource --secret-id "$EXISTING" --tags "${TAGS[@]}"
  fi
  rm -rf "$WORK_DIR"
  echo "$NAME: stored $COUNT files from $CONFIG_DIR ($SIZE bytes), tagged $TAG_ENV=$SNAPSHOT_ENV $TAG_TAG=$TAG${COMMIT:+ $TAG_COMMIT=$COMMIT}"
}

pull(){
  if [ -d "$CONFIG_DIR" ] && [ -n "$(ls -A "$CONFIG_DIR")" ] && [ "$FORCE" != "true" ] && [ "$DRY_RUN" != "true" ]; then
    echo "Config dir $CONFIG_DIR is not empty.  Use --force to overwrite its files." >&2
    exit 1
  fi
  require_snapshot
  local SNAPSHOT
  SNAPSHOT=$(snapshot_name)
  if [ "$DRY_RUN" == "true" ]; then
    echo "$SNAPSHOT: would extract the config files to $CONFIG_DIR"
    return
  fi

  local VALUE
  VALUE=$(aws_sm get-secret-value --secret-id "$SNAPSHOT_ARN" --query SecretString --output text) \
    || { echo "Failed to read $SNAPSHOT" >&2; exit 1; }

  # The files are all decoded first, so that a bad snapshot leaves the config dir alone.
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

  mkdir -p "$CONFIG_DIR"
  local CONFIG_FILE COUNT=0
  for CONFIG_FILE in "$WORK_DIR"/* ; do {
    [ -f "$CONFIG_FILE" ] || continue
    NAME=$(basename "$CONFIG_FILE")
    cp "$CONFIG_FILE" "$CONFIG_DIR/$NAME"
    chmod 600 "$CONFIG_DIR/$NAME"
    echo "$NAME: written to $CONFIG_DIR"
    COUNT=$((COUNT + 1))
  } done
  rm -rf "$WORK_DIR"
  echo "Pulled $COUNT config files from $SNAPSHOT to $CONFIG_DIR"
}

list(){
  echo "Snapshots of env $SNAPSHOT_ENV"
  list_snapshots | jq -r 'sort_by(.LastChangedDate) | reverse | .[]
    | "\(.Name)\ttag \(.Tag)\t\(if .Commit != "" then "commit \(.Commit)\t" else "" end)changed \(.LastChangedDate)"'
}

delete(){
  require_snapshot
  local SNAPSHOT
  SNAPSHOT=$(snapshot_name)
  if [ "$DRY_RUN" == "true" ]; then
    echo "$SNAPSHOT: would delete$([ "$FORCE" == "true" ] && echo " at once" || echo ", with a recovery window of $RECOVERY_DAYS days")"
    return
  fi
  if [ "$FORCE" == "true" ]; then
    aws_sm delete-secret --secret-id "$SNAPSHOT_ARN" --force-delete-without-recovery > /dev/null
    echo "$SNAPSHOT: deleted"
  else
    aws_sm delete-secret --secret-id "$SNAPSHOT_ARN" --recovery-window-in-days $RECOVERY_DAYS > /dev/null
    echo "$SNAPSHOT: deleted, recoverable for $RECOVERY_DAYS days with 'aws secretsmanager restore-secret --secret-id $SNAPSHOT'"
  fi
}

ACTION="${1:-}"
shift || true

AFON_HOME=
SNAPSHOT_ENV=
TAG=
REGION=
FORCE=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)
      AFON_HOME="$2"; shift 2 ;;
    --env)
      SNAPSHOT_ENV="$2"; shift 2 ;;
    --tag)
      TAG="$2"; shift 2 ;;
    --region)
      REGION="$2"; shift 2 ;;
    --force)
      FORCE=true; shift ;;
    --dry-run)
      DRY_RUN=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$AFON_HOME" ] ; then
  AFON_HOME="$HOME/.afon"
  echo "Using AfonHome $AFON_HOME"
fi

# CONFIG_ENV is set by afon.sh when it has switched to a config env.
if [ -z "$SNAPSHOT_ENV" ] ; then
  SNAPSHOT_ENV="${CONFIG_ENV:-}"
fi
if [ -n "$SNAPSHOT_ENV" ] ; then
  CONFIG_DIR="$AFON_HOME/config-$SNAPSHOT_ENV"
else
  CONFIG_DIR="$AFON_HOME/config"
  SNAPSHOT_ENV="$DEFAULT_ENV"
fi
echo "Using env $SNAPSHOT_ENV"
echo "Using config dir $CONFIG_DIR"
if [ -n "$REGION" ] ; then
  echo "Using region $REGION"
fi

# All clouds are always stored, so that the config dir is restored whole.
CLOUD=
source $AFON_ROOT/scripts/config/config-files.sh
# The generic catalogs.properties is not among the files listed by config-files.sh, but is needed just the same
# as the catalogs-<cloud>.properties that are.
CONFIG_FILES="$CONFIG_FILES catalogs.properties"

case "$ACTION" in
  push|pull|delete)
    check_tools
    resolve_tag
    $ACTION
    ;;
  list)
    check_tools
    list
    ;;
  *)
    usage; exit 1 ;;
esac
