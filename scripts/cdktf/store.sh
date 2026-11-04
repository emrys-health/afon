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

case "$STORE_CLOUD" in
  aws)   CLI=aws ;;
  azure) CLI=az ;;
  gcp)   CLI=gcloud ;;
  *) echo "Unknown cloud $STORE_CLOUD: it must be aws, azure or gcp" >&2; exit 1 ;;
esac
if [ -n "$ACCOUNT" ] && [ "$STORE_CLOUD" != "azure" ]; then
  echo "--account is for azure only" >&2; exit 1
fi
if [ -n "$STORE_PROJECT" ] && [ "$STORE_CLOUD" != "gcp" ]; then
  echo "--project is for gcp only" >&2; exit 1
fi
if [ -n "$REGION" ] && [ "$STORE_CLOUD" == "azure" ]; then
  echo "--region is for aws and gcp only" >&2; exit 1
fi

# The env and the cdktf dir are set by afon.sh and afon-cli.sh, which have already switched the cdktf symlink to
# cdktf-<env> when given an env.
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
CDKTF_STORE_DIR="${CDKTF_DIR:-$AFON_HOME/cdktf}"
STORE_ENV="${CDKTF_ENV:-}"
if [ -z "$STORE_ENV" ] && [ -L "$CDKTF_STORE_DIR" ]; then
  STORE_ENV=$(basename "$(readlink "$CDKTF_STORE_DIR")")
  if [[ "$STORE_ENV" == cdktf-* ]]; then
    STORE_ENV="${STORE_ENV#cdktf-}"
  else
    STORE_ENV=
  fi
fi
if [ -z "$STORE_ENV" ] || [[ ! "$STORE_ENV" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "No cdktf env: give --env or --cdktf-env to afon.sh or afon-cli.sh, or switch to one with cdktf-switch" >&2
  exit 1
fi
echo "Using env $STORE_ENV"
echo "Using cdktf dir $CDKTF_STORE_DIR"
echo "Using cloud $STORE_CLOUD"

if [ -n "$STACK" ] && [[ ! "$STACK" =~ ^[A-Za-z0-9][A-Za-z0-9-]*$ ]]; then
  echo "Invalid stack id $STACK, eg aws-keycloak" >&2; exit 1
fi
[ -n "$STACK" ] && echo "Using stack $STACK"

check_tools(){
  local TOOL
  for TOOL in $CLI jq ; do {
    if ! command -v "$TOOL" > /dev/null 2>&1; then
      echo "Requires $TOOL on the PATH" >&2
      exit 1
    fi
  } done
  # Stops early, rather than with a failure of the first call, if the CLI is not logged in.
  case "$STORE_CLOUD" in
    aws)   aws sts get-caller-identity > /dev/null 2>&1 || fail "Not logged in to AWS: run aws-login" ;;
    azure) az account show > /dev/null 2>&1 || fail "Not logged in to Azure: run azure-login" ;;
    gcp)   gcloud auth print-access-token > /dev/null 2>&1 || fail "Not logged in to GCP: run gcp-login" ;;
  esac
}

# Says what the signed-in user needs to access the store, and what to do about it.  LEVEL is what was refused: read
# (list and read the objects), write (also write and delete them) or create (also create the bucket or container).
access_hint(){
  local LEVEL="$1" WHAT="$1"
  [ "$LEVEL" == "create" ] && WHAT="write to, or create if missing,"
  echo "Access to the store was refused: the signed-in user lacks the permission to $WHAT the store." >&2
  case "$STORE_CLOUD" in
    aws)
      echo "Ask an AWS admin to grant the user, on the bucket $BUCKET and its objects (arn:aws:s3:::$BUCKET, arn:aws:s3:::$BUCKET/*):" >&2
      echo "  read:    s3:ListBucket, s3:GetObject" >&2
      echo "  write:   read, plus s3:PutObject, s3:DeleteObject" >&2
      echo "  create:  write, plus s3:CreateBucket, s3:PutBucketPublicAccessBlock, s3:PutEncryptionConfiguration," >&2
      echo "           s3:PutBucketVersioning" >&2
      echo "or log in as a user who has them: aws-login." >&2 ;;
    azure)
      local ROLE="Storage Blob Data Contributor"
      [ "$LEVEL" == "read" ] && ROLE="Storage Blob Data Reader"
      echo "On the data of storage account $ACCOUNT the user needs the role '$ROLE' (to $LEVEL), which the subscription" >&2
      echo "roles (Owner, Contributor) do not include.  Ask an Azure admin to assign it, or, with the permission to, run:" >&2
      echo "  az role assignment create --role \"$ROLE\" --assignee \$(az ad signed-in-user show --query id -o tsv) \\" >&2
      echo "    --scope \$(az storage account show --name $ACCOUNT --query id -o tsv)" >&2
      echo "A new role may take a few minutes to apply.  Or log in as a user who has it: azure-login." >&2 ;;
    gcp)
      local ROLE="roles/storage.objectAdmin"
      [ "$LEVEL" == "read" ] && ROLE="roles/storage.objectViewer"
      [ "$LEVEL" == "create" ] && ROLE="roles/storage.admin"
      echo "On the bucket $BUCKET of project $STORE_PROJECT the user needs the role $ROLE (to $LEVEL).  Ask a GCP admin to" >&2
      echo "grant it, or, with the permission to, run:" >&2
      if [ "$LEVEL" == "create" ]; then
        echo "  gcloud projects add-iam-policy-binding $STORE_PROJECT --member user:\$(gcloud config get-value account) --role $ROLE" >&2
      else
        echo "  gcloud storage buckets add-iam-policy-binding gs://$BUCKET --member user:\$(gcloud config get-value account) --role $ROLE" >&2
      fi
      echo "Or log in as a user who has it: gcp-login." >&2 ;;
  esac
}

check_tag(){
  if [ -z "$TAG" ]; then
    echo "Missing tag, required by $ACTION" >&2
    usage; exit 1
  fi
  if [[ ! "$TAG" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "Invalid tag $TAG: it must be letters, digits and . _ -" >&2
    exit 1
  fi
  echo "Using tag $TAG"
}

# Removes the work dir, which holds secrets, and stops.
fail(){
  echo "$1" >&2
  exit 1
}

WORK_DIR=$(mktemp -d)
chmod 700 "$WORK_DIR"
trap 'rm -rf "$WORK_DIR"' EXIT

# Prints the value of a property of the config of the env, which may be set in terms of others, eg
# ${ENV_NAME}afonstorageaccount.  The config dir of the env is read rather than via the config symlink, which may not
# point to it, eg to pull into a new env.  Prints nothing if there is none.
config_property(){
  local PROPERTY="$1" CLOUD_NAME="$2" FILE
  (
    set +eu
    set -a
    for FILE in afon.properties "afon-$CLOUD_NAME.properties" ; do {
      [ -f "$AFON_HOME/config-$STORE_ENV/$FILE" ] && source "$AFON_HOME/config-$STORE_ENV/$FILE" > /dev/null 2>&1
    } done
    printf '%s' "${!PROPERTY:-}"
  )
}

# Sets what depends on the cloud: the bucket (or the container) of the store, and where it is.
set_store(){
  case "$STORE_CLOUD" in
    aws)
      local ACCOUNT_ID
      ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text) \
        || fail "Failed to get the AWS account: is the aws CLI logged in?"
      BUCKET="$STORE_NAME-$ACCOUNT_ID"
      [ -z "$REGION" ] && REGION=$(aws configure get region 2>/dev/null || true)
      [ -n "$REGION" ] && echo "Using region $REGION"
      echo "Using bucket $BUCKET" ;;
    azure)
      if [ -z "$ACCOUNT" ]; then
        ACCOUNT=$(config_property AZURE_STORAGE_ACCOUNT azure)
      fi
      if [ -z "$ACCOUNT" ]; then
        ACCOUNT="${STORE_ENV}afonstorageaccount"
        echo "No AZURE_STORAGE_ACCOUNT in the config of env $STORE_ENV: using the default $ACCOUNT, or give --account"
      fi
      BUCKET="$STORE_NAME"
      echo "Using storage account $ACCOUNT, container $BUCKET" ;;
    gcp)
      if [ -z "$STORE_PROJECT" ]; then
        STORE_PROJECT=$(config_property GCP_PROJECT_ID gcp)
      fi
      if [ -z "$STORE_PROJECT" ]; then
        STORE_PROJECT=$(gcloud config get-value project 2>/dev/null || true)
        [ -n "$STORE_PROJECT" ] && echo "No GCP_PROJECT_ID in the config of env $STORE_ENV: using the project of gcloud, or give --project"
      fi
      [ -z "$STORE_PROJECT" ] && fail "No GCP project: give --project, set GCP_PROJECT_ID in afon-gcp.properties, or set one in gcloud"
      [ -z "$REGION" ] && REGION=$(config_property GCP_REGION gcp)
      BUCKET="$STORE_NAME-$STORE_PROJECT"
      echo "Using project $STORE_PROJECT, bucket $BUCKET" ;;
  esac
}

# What follows is the part that depends on the cloud.  The objects are named by KEY, relative to the bucket.

# Succeeds if the bucket (container) exists, fails if it does not.  Any other failure, eg a refused access, stops.
store_exists(){
  local ERR="$WORK_DIR/exists.err" EXISTS NEED=read
  # What the action needs, for the hint if the access is refused: push may even have to create the store.
  [ "$ACTION" == "push" ] && NEED=create
  [ "$ACTION" == "delete" ] && NEED=write
  case "$STORE_CLOUD" in
    aws)
      aws s3api head-bucket --bucket "$BUCKET" > /dev/null 2> "$ERR" && return 0
      grep -qE "404|Not Found|NoSuchBucket" "$ERR" && return 1
      cat "$ERR" >&2; access_hint $NEED; exit 1 ;;
    azure)
      EXISTS=$(az storage container exists --account-name "$ACCOUNT" --name "$BUCKET" --auth-mode login --query exists -o tsv 2> "$ERR") \
        || { cat "$ERR" >&2; access_hint $NEED; exit 1; }
      [ "$EXISTS" == "true" ] ;;
    gcp)
      gcloud storage buckets describe "gs://$BUCKET" --project "$STORE_PROJECT" > /dev/null 2> "$ERR" && return 0
      grep -qiE "404|not found|does not exist" "$ERR" && return 1
      cat "$ERR" >&2; access_hint $NEED; exit 1 ;;
  esac
}

# Creates the bucket (container), private, encrypted and versioned.
store_create(){
  echo "Creating $BUCKET"
  case "$STORE_CLOUD" in
    aws)
      if [ -z "$REGION" ]; then
        fail "No AWS region: give --region, or set one in the aws CLI"
      elif [ "$REGION" == "us-east-1" ]; then
        aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" > /dev/null || return 1
      else
        aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
          --create-bucket-configuration LocationConstraint="$REGION" > /dev/null || return 1
      fi
      aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
        BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true || return 1
      aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
        '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' || return 1
      aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled ;;
    azure)
      # The container is private by default and the data is encrypted at rest.  Blob versioning is a setting of the
      # storage account, which afon does not change here.
      az storage container create --account-name "$ACCOUNT" --name "$BUCKET" --auth-mode login --public-access off -o none \
 ;;
    gcp)
      [ -z "$REGION" ] && fail "No GCP location: give --region, or set GCP_REGION in afon-gcp.properties"
      gcloud storage buckets create "gs://$BUCKET" --project "$STORE_PROJECT" --location "$REGION" \
        --uniform-bucket-level-access --public-access-prevention > /dev/null || return 1
      gcloud storage buckets update "gs://$BUCKET" --project "$STORE_PROJECT" --versioning > /dev/null ;;
  esac
}

# Prints the keys of the objects with the prefix.
store_keys(){
  local PREFIX="$1"
  case "$STORE_CLOUD" in
    aws)
      aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$PREFIX" --query 'Contents[].Key' --output json \
        | jq -r '.[]?' ;;
    azure)
      az storage blob list --account-name "$ACCOUNT" --container-name "$BUCKET" --prefix "$PREFIX" \
        --auth-mode login --query '[].name' -o json | jq -r '.[]' ;;
    gcp)
      # Nothing matching is not an error, whereas a refused access is.
      local OUT
      if ! OUT=$(gcloud storage ls --recursive "gs://$BUCKET/$PREFIX" --project "$STORE_PROJECT" 2>"$WORK_DIR/ls.err"); then
        grep -qiE "matched no objects|not found|404" "$WORK_DIR/ls.err" || { cat "$WORK_DIR/ls.err" >&2; return 1; }
        OUT=
      fi
      printf '%s\n' "$OUT" | grep -v ':$' | grep -v '/$' | sed "s|^gs://$BUCKET/||" | grep . || true ;;
  esac
}

# Uploads the file to the key.
store_put(){
  local FILE="$1" KEY="$2"
  case "$STORE_CLOUD" in
    aws)   aws s3 cp "$FILE" "s3://$BUCKET/$KEY" --only-show-errors ;;
    azure) az storage blob upload --account-name "$ACCOUNT" --container-name "$BUCKET" --name "$KEY" --file "$FILE" \
             --auth-mode login --overwrite --no-progress -o none ;;
    gcp)   gcloud storage cp "$FILE" "gs://$BUCKET/$KEY" --project "$STORE_PROJECT" --quiet --no-user-output-enabled ;;
  esac
}

# Downloads the key to the file.
store_get(){
  local KEY="$1" FILE="$2"
  case "$STORE_CLOUD" in
    aws)   aws s3 cp "s3://$BUCKET/$KEY" "$FILE" --only-show-errors ;;
    azure) az storage blob download --account-name "$ACCOUNT" --container-name "$BUCKET" --name "$KEY" --file "$FILE" \
             --auth-mode login --no-progress -o none ;;
    gcp)   gcloud storage cp "gs://$BUCKET/$KEY" "$FILE" --project "$STORE_PROJECT" --quiet --no-user-output-enabled ;;
  esac
}

# Deletes the key.
store_delete(){
  local KEY="$1"
  case "$STORE_CLOUD" in
    aws)   aws s3 rm "s3://$BUCKET/$KEY" --only-show-errors ;;
    azure) az storage blob delete --account-name "$ACCOUNT" --container-name "$BUCKET" --name "$KEY" \
             --auth-mode login -o none ;;
    gcp)   gcloud storage rm "gs://$BUCKET/$KEY" --project "$STORE_PROJECT" --quiet --no-user-output-enabled ;;
  esac
}

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
    printf '%s\n' "$FILES" | sed 's/^/  /'
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
    printf '%s\n' "${EXISTING[@]}" | sed 's/^/  /' >&2
    fail "$STORE_ENV/$TAG already has ${#EXISTING[@]} of these files.  Use --force to replace them, or delete them first."
  fi
  for REL in $FILES ; do {
    store_put "$CDKTF_STORE_DIR/$REL" "$STORE_ENV/$TAG/$REL" || { access_hint write; fail "Failed to store $REL"; }
    echo "$REL: stored"
  } done
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
  for REL in ${CHANGED[@]+"${CHANGED[@]}"} ; do echo "$REL: differs from $STORE_ENV/$TAG" ; done
  for REL in ${ADDED[@]+"${ADDED[@]}"} ; do echo "$REL: missing from $CDKTF_STORE_DIR" ; done
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
    echo "$REL: written to $CDKTF_STORE_DIR"
  } done
  echo "Pulled $((${#CHANGED[@]} + ${#ADDED[@]})) files from $STORE_ENV/$TAG to $CDKTF_STORE_DIR"
}

list(){
  if ! store_exists; then
    echo "No store $BUCKET: nothing was pushed yet"
    return
  fi
  echo "Snapshots of env $STORE_ENV"
  # Each key is <env>/<tag>/<file>: the tag, and the stack of the file, are printed one per line, then grouped by tag.
  local ALL_KEYS
  ALL_KEYS=$(store_keys "$STORE_ENV/") || { access_hint read; exit 1; }
  printf '%s\n' "$ALL_KEYS" | sed "s|^$STORE_ENV/||" | while IFS= read -r KEY ; do {
    local TAG_PART="${KEY%%/*}" REL="${KEY#*/}"
    if [[ "$REL" =~ ^terraform\.(.+)\.tfstate$ ]]; then
      echo "$TAG_PART ${BASH_REMATCH[1]}"
    elif [[ "$REL" =~ ^stacks/([^/]+)/ ]]; then
      echo "$TAG_PART ${BASH_REMATCH[1]}"
    fi
  } done | sort -u | { [ -z "$STACK" ] && cat || grep -E " $STACK$" || true; } \
    | awk '{ stacks[$1] = stacks[$1] " " $2 } END { for (t in stacks) print t "\tstacks:" stacks[t] }' | sort
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
    printf '%s\n' "$FILES" | sed 's/^/  /'
    return
  fi
  for REL in $FILES ; do {
    store_delete "$STORE_ENV/$TAG/$REL" || { access_hint write; fail "Failed to delete $REL"; }
    echo "$REL: deleted"
  } done
  echo "$STORE_ENV/$TAG: deleted $COUNT files of $(scope) from $BUCKET"
}

case "$ACTION" in
  push|pull|delete)
    check_tools
    check_tag
    set_store
    echo "Running $ACTION of $STORE_ENV/$TAG${STACK:+ (stack $STACK)} in $BUCKET"
    $ACTION
    ;;
  list)
    check_tools
    set_store
    echo "Running list of $STORE_ENV${STACK:+ (stack $STACK)} in $BUCKET"
    list
    ;;
  *)
    usage; exit 1 ;;
esac
