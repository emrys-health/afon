#!/usr/bin/env bash
# Stores snapshots of the cdktf state and plans in the object storage of a cloud, and retrieves them by a tag:
# AWS S3 (the default), Azure Blob Storage or GCP Cloud Storage.  Unlike config-vault, which keeps the small config
# files in the secret store of the cloud, the objects have no size limit, so the state files and the plans fit.
#
# ./afon.sh -a cdktf-store {push|pull|delete <tag>|list} [<stack-id>] [-e|--env|--cdktf-env <env>] [-c|--cloud {aws|azure|gcp}] [-- [--region <region>] [--account <name>] [--project <id>] [--force] [--dry-run]]
# afon-cli.sh cdktf-store {push|pull|delete <tag>|list} [<stack-id>] [-e|--env|--cdktf-env <env>] [-c|--cloud {aws|azure|gcp}] [--region <region>] [--account <name>] [--project <id>] [--force] [--dry-run]
# (the -- is needed by afon.sh before the script's own options, eg: ./afon.sh -a cdktf-store push v1 -e dev -- --force)
#
# The env and the cdktf dir are the global ones of afon.sh and afon-cli.sh (--env, --cdktf-env, AFON_ENV, AFON_CDKTF_ENV,
# --cdktf, AFON_CDKTF).  Without a global env, the env is the one that the <afon-home>/cdktf symlink points to.
#
# What is stored, for each stack of the cdktf dir, or just for the <stack-id> given, eg aws-keycloak:
#   terraform.<stack-id>.tfstate            the tofu state
#   stacks/<stack-id>/**                    the plan: cdk.tf.json and assets, but not .terraform nor .terraform.lock.hcl,
#                                           which 'tofu init' creates again
# The kubeconfig (kube/) and the state backups (*.backup) are left out.
# The state and the plan hold secrets in plain text (passwords, tokens), so the store is private and encrypted, and
# versioned, so that what a push --force or a delete replaced can be recovered from the previous versions.
#
# The tag is required by push, pull and delete.  It is free-form text of letters, digits and . _ -, eg a release, a
# git tag or a name.  The objects are named <env>/<tag>/<file>, eg dev/v1/terraform.aws-keycloak.tfstate, in the
# store of the cloud:
#   aws    the S3 bucket afon-cdktf-<account-id>, created on first push in the region of --region, else of the aws CLI
#   azure  the container afon-cdktf of the storage account of afon-azure.properties (AZURE_STORAGE_ACCOUNT) or --account
#   gcp    the Cloud Storage bucket afon-cdktf-<project-id>, created on first push in the location of --region, else
#          GCP_REGION of afon-gcp.properties
# (the bucket names of aws and gcp are global, hence the account or project in them.)
#
# 'push' stores the files of the stack, or of all the stacks, in the snapshot of the tag.  Stacks may be pushed to the
# same tag one at a time.  Replacing files that the snapshot already has needs --force.
# 'pull' compares the files of the snapshot, or of the stack only, with those of the cdktf dir, and writes back those
# that differ or are missing.  Overwriting the files that differ needs --force.
# 'list' lists the snapshots of the env and the stacks they hold, 'delete' removes the snapshot of the tag, or just the
# files of the stack.
#
# Requires jq and the CLI of the cloud (aws, az or gcloud), logged in with the permissions to list, read and write the
# objects, and to create the bucket or container on first push.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {push|pull|delete <tag>|list} [<stack-id>] [--region <region>] [--account <name>] [--project <id>] [--force] [--dry-run]"
  echo "actions:  push   :  writes the state and plan of the stack, or of all the stacks, to the snapshot of the tag.  Refuses"
  echo "                    to replace files that the snapshot already has without --force"
  echo "          pull   :  writes the files held in the snapshot of the tag back into the cdktf dir, if they differ."
  echo "                    Refuses to overwrite the files that differ without --force"
  echo "          list   :  lists the snapshots of the env, and the stacks that they hold"
  echo "          delete :  removes the snapshot of the tag, or just the files of the stack"
  echo "cloud:      the cloud of the store: aws (S3), azure (Blob Storage) or gcp (Cloud Storage)."
  echo "            It is the global one, given by -c or --cloud of afon.sh or afon-cli.sh, or AFON_CLOUD.  Defaults to aws,"
  echo "            also if it is ALL."
  echo "tag:        the tag of the snapshot, of letters, digits and . _ -.  Required by push, pull and delete."
  echo "stack-id:   the stack, eg aws-keycloak, to push, pull or delete on its own.  Defaults to all the stacks."
  echo "region:     the AWS region or GCP location of the bucket created on first push.  Defaults to the one of the aws CLI,"
  echo "            or GCP_REGION of afon-gcp.properties.  Only for aws and gcp."
  echo "account:    the Azure storage account.  Defaults to AZURE_STORAGE_ACCOUNT of afon-azure.properties.  Only for azure."
  echo "project:    the GCP project.  Defaults to GCP_PROJECT_ID of afon-gcp.properties.  Only for gcp."
  echo "force:      the flag to let push replace files of the snapshot, and pull overwrite the files that differ."
  echo "dry-run:    the flag to report what would be done, without writing anything."
  echo "The env is the global one, given by --env or --cdktf-env of afon.sh or afon-cli.sh, else the one that"
  echo "<afon-home>/cdktf points to.  The cdktf dir is the global one (--cdktf, AFON_CDKTF)."
}

STORE_NAME="afon-cdktf"

ACTION="${1:-}"
shift || true

TAG=
STACK=
STORE_CLOUD=$(echo "${CLOUD:-}" | tr '[:upper:]' '[:lower:]')
if [ -z "$STORE_CLOUD" ] || [ "$STORE_CLOUD" == "all" ]; then
  STORE_CLOUD=aws
fi
REGION=
ACCOUNT=
STORE_PROJECT=
FORCE=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      REGION="$2"; shift 2 ;;
    --account)
      ACCOUNT="$2"; shift 2 ;;
    --project)
      STORE_PROJECT="$2"; shift 2 ;;
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
      # list takes only the stack, the other actions the tag, then the stack.
      if [ "$ACTION" != "list" ] && [ -z "$TAG" ]; then
        TAG="$1"
      elif [ -z "$STACK" ]; then
        STACK="$1"
      else
        echo "Unexpected arg: $1" >&2; usage; exit 1
      fi
      shift ;;
  esac
done

source "$AFON_ROOT/scripts/store/object-store-source.sh"
check_store_options
resolve_store_env
echo "Using env $STORE_ENV"
echo "Using cdktf dir $CDKTF_STORE_DIR"
echo "Using cloud $STORE_CLOUD"

if [ -n "$STACK" ] && [[ ! "$STACK" =~ ^[A-Za-z0-9][A-Za-z0-9-]*$ ]]; then
  echo "Invalid stack id $STACK, eg aws-keycloak" >&2; exit 1
fi
[ -n "$STACK" ] && echo "Using stack $STACK"

# What follows is independent of the cloud.  The files are named by their path relative to the cdktf dir.

# Prints the paths of the local state and plan files of the stack, or of all the stacks.
local_files(){
  cd "$CDKTF_STORE_DIR" 2>/dev/null || return 0
  if [ -n "$STACK" ]; then
    [ -f "terraform.$STACK.tfstate" ] && echo "terraform.$STACK.tfstate"
    [ -d "stacks/$STACK" ] && find "stacks/$STACK" -type f -not -path '*/.terraform/*' -not -name '.terraform.lock.hcl'
  else
    ls terraform.*.tfstate 2>/dev/null || true
    [ -d stacks ] && find stacks -type f -not -path '*/.terraform/*' -not -name '.terraform.lock.hcl'
  fi
  return 0
}

# Whether the path, relative to the snapshot, belongs to the stack, or to any if there is none.
in_scope(){
  local REL="$1"
  [ -z "$STACK" ] && return 0
  [ "$REL" == "terraform.$STACK.tfstate" ] || [[ "$REL" == stacks/$STACK/* ]]
}

# Prints the paths, relative to the snapshot, of the objects of the snapshot in scope.
remote_files(){
  local PREFIX="$STORE_ENV/$TAG/" KEY REL
  local KEYS
  KEYS=$(store_keys "$PREFIX") || { access_hint read; exit 1; }
  printf '%s\n' "$KEYS" | while IFS= read -r KEY ; do {
    [ -z "$KEY" ] && continue
    REL="${KEY#$PREFIX}"
    in_scope "$REL" && echo "$REL"
  } done || true
}

# Prints the units of the paths on stdin, each once, in order: the state file as it is, and the plan of a stack, its many assets,
# as stacks/<stack-id>.  They are what is reported, rather than each file.
units(){
  awk '{ u = $0; if (match($0, /^stacks\/[^\/]+\//)) u = substr($0, 1, RLENGTH - 1); if (!(u in seen)) { seen[u] = 1; print u } }'
}

# Prints a line for each unit of the paths given, ending with the text, eg "stored".
report_units(){
  local TEXT="$1"
  shift
  [ $# -gt 0 ] || return 0
  printf '%s\n' "$@" | units | sed "s|\$|: $TEXT|"
}

scope(){
  if [ -n "$STACK" ]; then echo "stack $STACK"; else echo "all the stacks"; fi
}

push(){
  local FILES COUNT REL EXISTING=() KEY
  FILES=$(local_files)
  COUNT=$(printf '%s' "$FILES" | grep -c . || true)
  if [ "$COUNT" -eq 0 ]; then
    fail "No state nor plan to push for $(scope) in $CDKTF_STORE_DIR"
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "$STORE_ENV/$TAG: would store $COUNT files of $(scope) from $CDKTF_STORE_DIR"
    printf '%s\n' "$FILES" | units | sed 's/^/  /'
    return
  fi
  if ! store_exists; then
    store_create || { access_hint create; fail "Failed to create $BUCKET"; }
  fi
  # The files that the snapshot already has are replaced only with --force.
  local REMOTE
  REMOTE=$(remote_files)
  for REL in $FILES ; do {
    if printf '%s\n' "$REMOTE" | grep -qxF -- "$REL"; then
      EXISTING+=("$REL")
    fi
  } done
  if [ ${#EXISTING[@]} -gt 0 ] && [ "$FORCE" != "true" ]; then
    printf '%s\n' "${EXISTING[@]}" | units | sed 's/^/  /' >&2
    fail "$STORE_ENV/$TAG already has ${#EXISTING[@]} of these files.  Use --force to replace them, or delete them first."
  fi
  for REL in $FILES ; do {
    store_put "$CDKTF_STORE_DIR/$REL" "$STORE_ENV/$TAG/$REL" || { access_hint write; fail "Failed to store $REL"; }
  } done
  printf '%s\n' "$FILES" | units | sed 's|$|: stored|'
  echo "$STORE_ENV/$TAG: stored $COUNT files of $(scope) in $BUCKET"
}

pull(){
  if ! store_exists; then
    fail "No store $BUCKET: nothing was pushed yet"
  fi
  local FILES REL
  FILES=$(remote_files)
  if [ -z "$FILES" ]; then
    fail "No snapshot of env $STORE_ENV tagged $TAG with files of $(scope)"
  fi
  # The files are all downloaded first, so that a failure leaves the cdktf dir alone, and so that they can be
  # compared with those of the cdktf dir.
  umask 077
  for REL in $FILES ; do {
    mkdir -p "$WORK_DIR/pull/$(dirname "$REL")"
    # Only plain relative paths are taken, whatever the store holds.
    if [[ "$REL" == /* ]] || [[ "/$REL/" == */../* ]]; then
      fail "$REL: not a plain path"
    fi
    store_get "$STORE_ENV/$TAG/$REL" "$WORK_DIR/pull/$REL" || { access_hint read; fail "Failed to read $REL"; }
  } done

  local CHANGED=() ADDED=() UNCHANGED=0
  for REL in $FILES ; do {
    if [ ! -f "$CDKTF_STORE_DIR/$REL" ]; then
      ADDED+=("$REL")
    elif cmp -s "$WORK_DIR/pull/$REL" "$CDKTF_STORE_DIR/$REL"; then
      UNCHANGED=$((UNCHANGED + 1))
    else
      CHANGED+=("$REL")
    fi
  } done
  report_units "differs from $STORE_ENV/$TAG" ${CHANGED[@]+"${CHANGED[@]}"}
  report_units "missing from $CDKTF_STORE_DIR" ${ADDED[@]+"${ADDED[@]}"}
  echo "$UNCHANGED files unchanged, ${#CHANGED[@]} changed and ${#ADDED[@]} missing in $CDKTF_STORE_DIR"

  if [ ${#CHANGED[@]} -eq 0 ] && [ ${#ADDED[@]} -eq 0 ]; then
    echo "Cdktf dir $CDKTF_STORE_DIR is up to date with $STORE_ENV/$TAG"
    return
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "$STORE_ENV/$TAG: would write $((${#CHANGED[@]} + ${#ADDED[@]})) files to $CDKTF_STORE_DIR$([ ${#CHANGED[@]} -gt 0 ] && [ "$FORCE" != "true" ] && echo ", with --force")"
    return
  fi
  if [ ${#CHANGED[@]} -gt 0 ] && [ "$FORCE" != "true" ]; then
    fail "Cdktf dir $CDKTF_STORE_DIR has ${#CHANGED[@]} files that differ from $STORE_ENV/$TAG.  Use --force to overwrite them."
  fi
  for REL in ${CHANGED[@]+"${CHANGED[@]}"} ${ADDED[@]+"${ADDED[@]}"} ; do {
    mkdir -p "$CDKTF_STORE_DIR/$(dirname "$REL")"
    cp "$WORK_DIR/pull/$REL" "$CDKTF_STORE_DIR/$REL"
    chmod 600 "$CDKTF_STORE_DIR/$REL"
  } done
  report_units "written to $CDKTF_STORE_DIR" ${CHANGED[@]+"${CHANGED[@]}"} ${ADDED[@]+"${ADDED[@]}"}
  echo "Pulled $((${#CHANGED[@]} + ${#ADDED[@]})) files from $STORE_ENV/$TAG to $CDKTF_STORE_DIR"
}

list(){
  if ! store_exists; then
    echo "No store $BUCKET: nothing was pushed yet"
    return
  fi
  echo "Snapshots of env $STORE_ENV"
  # Each key is <env>/<tag>/<file>.  The tag, the stack of the file and the time it changed are printed one per line,
  # then grouped by tag, whose time is that of its latest change.
  local OBJECTS
  OBJECTS=$(store_objects "$STORE_ENV/") || { access_hint read; exit 1; }
  printf '%s\n' "$OBJECTS" | sed "s|^$STORE_ENV/||" | while IFS=$'\t' read -r KEY CHANGED ; do {
    [ -z "$KEY" ] && continue
    local TAG_PART="${KEY%%/*}" REL="${KEY#*/}"
    if [[ "$REL" =~ ^terraform\.(.+)\.tfstate$ ]]; then
      echo "$TAG_PART ${BASH_REMATCH[1]} $CHANGED"
    elif [[ "$REL" =~ ^stacks/([^/]+)/ ]]; then
      echo "$TAG_PART ${BASH_REMATCH[1]} $CHANGED"
    fi
  } done | { [ -z "$STACK" ] && cat || grep -E " $STACK " || true; } | sort \
    | awk -v p="$STORE_NAME" -v e="$STORE_ENV" '{
        if (!(($1, $2) in seen)) { seen[$1, $2] = 1; stacks[$1] = stacks[$1] " " $2 }
        if ($3 > changed[$1]) changed[$1] = $3
      } END { for (t in stacks) print p "/" e "/" t "\ttag " t "\tchanged " changed[t] "\tstacks:" stacks[t] }' \
    | sort -t$'\t' -k3,3r
}

delete(){
  if ! store_exists; then
    fail "No store $BUCKET: nothing was pushed yet"
  fi
  local FILES REL COUNT
  FILES=$(remote_files)
  COUNT=$(printf '%s' "$FILES" | grep -c . || true)
  if [ "$COUNT" -eq 0 ]; then
    fail "No snapshot of env $STORE_ENV tagged $TAG with files of $(scope)"
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "$STORE_ENV/$TAG: would delete $COUNT files of $(scope)"
    printf '%s\n' "$FILES" | units | sed 's/^/  /'
    return
  fi
  for REL in $FILES ; do {
    store_delete "$STORE_ENV/$TAG/$REL" || { access_hint write; fail "Failed to delete $REL"; }
  } done
  printf '%s\n' "$FILES" | units | sed 's|$|: deleted|'
  echo "$STORE_ENV/$TAG: deleted $COUNT files of $(scope) from $BUCKET"
}

case "$ACTION" in
  push|pull|delete)
    check_tools
    check_tag
    set_store "$STORE_NAME"
    echo "Running $ACTION of $STORE_ENV/$TAG${STACK:+ (stack $STACK)} in $BUCKET"
    $ACTION
    ;;
  list)
    check_tools
    set_store "$STORE_NAME"
    echo "Running list of $STORE_ENV${STACK:+ (stack $STACK)} in $BUCKET"
    list
    ;;
  *)
    usage; exit 1 ;;
esac
