#!/usr/bin/env bash
# Stores the log files of a run in a store of its own in the object storage of a cloud, and retrieves them by a tag, for the logs of a
# run that is lost with its machine, eg a job of a GitHub workflow, whose tag is the run: AWS S3 (the default), Azure Blob Storage or GCP
# Cloud Storage.  The store is apart from that of the cdktf state and plans (cdktf-store), as the logs need other access: the job only
# writes to it, and an admin reads it.  It is private, encrypted and versioned, as that is.
#
# logs-store {push|pull|delete <tag>|list} [-e|--env|--cdktf-env <env>] [-c|--cloud {aws|azure|gcp}] [--region <region>] [--account <name>] [--project <id>] [--dry-run]
#
# What is stored are the files *.log of <afon-home>/logs, gzipped, as <env>/<tag>/<file>.gz, in the store of the cloud:
#   aws    the S3 bucket afon-logs-<account-id>, created on first push in the region of --region, else of the aws CLI
#   azure  the container afon-logs of the storage account of afon-azure.properties (AZURE_STORAGE_ACCOUNT) or --account
#   gcp    the Cloud Storage bucket afon-logs-<project-id>, created on first push in the location of --region, else
#          GCP_REGION of afon-gcp.properties
# The tag is free-form text of letters, digits and . _ -, eg 42483427579-1, the run and its attempt.
#
# 'push' stores the logs, 'list' lists the tags of the env, with their files and the time they were stored, 'pull' writes the
# logs of a tag, unzipped, to <afon-home>/logs/<tag>, and 'delete' removes the logs of a tag.  The env is the global one, as for
# cdktf-store.
#
# Requires jq and the CLI of the cloud (aws, az or gcloud), logged in with the permissions to list, read and write the objects, and to
# create the bucket or container on first push.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {push|pull|delete <tag>|list} [-c|--cloud {aws|azure|gcp}] [--region <region>] [--account <name>] [--project <id>] [--dry-run]"
  echo "actions:  push  :  stores the log files of AFON_HOME/logs, gzipped, as <env>/<tag>"
  echo "          list  :  lists the tags of the logs stored for the env"
  echo "          pull  :  writes the logs stored as the tag, unzipped, to AFON_HOME/logs/<tag>"
  echo "          delete:  removes the logs stored as the tag"
  echo "cloud:      the cloud of the store: aws (S3), azure (Blob Storage) or gcp (Cloud Storage), given by -c or --cloud"
  echo "            (or AFON_CLOUD).  Without it, aws, which it says."
  echo "tag:        the tag of the logs, of letters, digits and . _ -.  Required by push, pull and delete."
  echo "region:     the AWS region or GCP location of the bucket created on first push.  Only for aws and gcp."
  echo "account:    the Azure storage account.  Defaults to AZURE_STORAGE_ACCOUNT of afon-azure.properties.  Only for azure."
  echo "project:    the GCP project.  Defaults to GCP_PROJECT_ID of afon-gcp.properties.  Only for gcp."
  echo "dry-run:    the flag to report what would be done, without writing anything."
  echo "The env is the global one, given by --env or --cdktf-env, else the one that"
  echo "<afon-home>/cdktf points to."
}

STORE_NAME="afon-logs"

ACTION="${1:-}"
shift || true

TAG=
REGION=
ACCOUNT=
STORE_PROJECT=
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      REGION="$2"; shift 2 ;;
    --account)
      ACCOUNT="$2"; shift 2 ;;
    --project)
      STORE_PROJECT="$2"; shift 2 ;;
    --dry-run)
      DRY_RUN=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    --)
      # Separates the options of the script, which the command may pass on.
      shift ;;
    -*)
      echo "Unknown option: $1" >&2; usage; exit 1 ;;
    *)
      if [ "$ACTION" != "list" ] && [ -z "$TAG" ]; then
        TAG="$1"
      else
        echo "Unexpected arg: $1" >&2; usage; exit 1
      fi
      shift ;;
  esac
done

source "$AFON_ROOT/scripts/store/object-store-source.sh"
check_store_options
resolve_store_env

LOGS_DIR="$AFON_HOME/logs"

push(){
  local FILES=() F NAME
  for F in "$LOGS_DIR"/*.log ; do [ -f "$F" ] && FILES+=("$F") ; done
  if [ ${#FILES[@]} -eq 0 ]; then
    fail "No log in $LOGS_DIR"
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "$STORE_ENV/$TAG: would store ${#FILES[@]} log files from $LOGS_DIR in $BUCKET"
    return
  fi
  if ! store_exists; then
    store_create || { access_hint create; fail "Failed to create $BUCKET"; }
  fi
  for F in "${FILES[@]}" ; do {
    NAME=$(basename "$F")
    gzip -c "$F" > "$WORK_DIR/$NAME.gz"
    store_put "$WORK_DIR/$NAME.gz" "$STORE_ENV/$TAG/$NAME.gz" || { access_hint write; fail "Failed to store $NAME"; }
    rm -f "$WORK_DIR/$NAME.gz"
  } done
  echo "$STORE_ENV/$TAG: stored ${#FILES[@]} log files in $BUCKET"
}

list(){
  if ! store_exists; then
    echo "No store $BUCKET: nothing was pushed yet"
    return
  fi
  echo "Logs of env $STORE_ENV"
  # Each key is <env>/<tag>/<file>: one line per tag, with the time of its latest file.
  store_objects "$STORE_ENV/" | sed "s|^$STORE_ENV/||" \
    | awk -F'\t' '{ split($1, p, "/"); n[p[1]]++; if ($2 > c[p[1]]) c[p[1]] = $2 } END { for (l in n) print l "\t" n[l] " files\tchanged " c[l] }' \
    | sort -t$'\t' -k3,3r || { access_hint read; exit 1; }
}

pull(){
  if ! store_exists; then
    fail "No store $BUCKET: nothing was pushed yet"
  fi
  local KEYS KEY NAME DEST="$LOGS_DIR/$TAG" COUNT=0
  KEYS=$(store_keys "$STORE_ENV/$TAG/") || { access_hint read; exit 1; }
  if [ -z "$KEYS" ]; then
    fail "No logs of env $STORE_ENV stored as $TAG: see logs-store list"
  fi
  umask 077
  mkdir -p "$DEST"
  for KEY in $KEYS ; do {
    NAME=$(basename "$KEY" .gz)
    store_get "$KEY" "$WORK_DIR/$NAME.gz" || { access_hint read; fail "Failed to read $NAME"; }
    gunzip -c "$WORK_DIR/$NAME.gz" > "$DEST/$NAME"
    rm -f "$WORK_DIR/$NAME.gz"
    COUNT=$((COUNT + 1))
  } done
  echo "Pulled $COUNT log files of $STORE_ENV/$TAG from $BUCKET to $DEST"
}

delete(){
  if ! store_exists; then
    fail "No store $BUCKET: nothing was pushed yet"
  fi
  local KEYS KEY COUNT
  KEYS=$(store_keys "$STORE_ENV/$TAG/") || { access_hint read; exit 1; }
  COUNT=$(printf '%s' "$KEYS" | grep -c . || true)
  if [ "$COUNT" -eq 0 ]; then
    fail "No logs of env $STORE_ENV stored as $TAG: see logs-store list"
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "$STORE_ENV/$TAG: would delete $COUNT log files"
    return
  fi
  for KEY in $KEYS ; do {
    store_delete "$KEY" || { access_hint write; fail "Failed to delete $(basename "$KEY")"; }
  } done
  echo "$STORE_ENV/$TAG: deleted $COUNT log files from $BUCKET"
}

case "$ACTION" in
  push|pull|delete)
    check_tools
    check_tag
    set_store "$STORE_NAME"
    echo "Running $ACTION of $STORE_ENV/$TAG on $STORE_CLOUD in $BUCKET"
    $ACTION
    ;;
  list)
    check_tools
    set_store "$STORE_NAME"
    echo "Running list of $STORE_ENV on $STORE_CLOUD in $BUCKET"
    list
    ;;
  *)
    usage; exit 1 ;;
esac
