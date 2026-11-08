#!/usr/bin/env bash
# Sourced by the commands that keep files in an object store of a cloud, cdktf-store and logs-store: the part that does not depend on
# the files, ie on what is stored.  The stores are private, encrypted and versioned: AWS S3 (the default), Azure Blob Storage or GCP
# Cloud Storage.  A store is a bucket (a container in Azure) named for what it holds and where it is, eg afon-cdktf-<account-id>.
#
# The sourcing script sets, before it uses the functions: ACTION, TAG (for check_tag), and REGION, ACCOUNT
# and STORE_PROJECT, as given by its options or empty.  Then:
#   check_store_options    sets STORE_CLOUD, the cloud (aws, azure or gcp) and CLI, its command line tool; stops if an option is not for it
#   resolve_store_env      sets STORE_ENV, the env of the objects, and AFON_HOME and CDKTF_STORE_DIR
#   check_tools, check_tag, set_store <name> (sets BUCKET), store_exists, store_create, store_objects, store_keys, store_put,
#   store_get and store_delete, which act on the objects named by KEY, relative to the bucket
# and WORK_DIR is a private temporary dir, removed when the script ends.

# Sets the cloud of the store, STORE_CLOUD, and stops if an option is not for it, and sets CLI.  The cloud is the global one, given by -c or
# --cloud, or AFON_CLOUD; else that of the stack, if a stack id is given, eg azure-full; else aws, which it says, as it is by default only.
check_store_options(){
  local OF_STACK=""
  case "${STACK:-}" in aws-*|azure-*|gcp-*) OF_STACK="${STACK%%-*}" ;; esac
  STORE_CLOUD=$(echo "${CLOUD:-}" | tr '[:upper:]' '[:lower:]')
  if [ -z "$STORE_CLOUD" ] || [ "$STORE_CLOUD" == "all" ]; then
    if [ -n "$OF_STACK" ]; then
      STORE_CLOUD="$OF_STACK"
    else
      STORE_CLOUD=aws
      echo "No cloud given: using aws.  Give -c or --cloud {aws|azure|gcp} for another."
    fi
  elif [ -n "$OF_STACK" ] && [ "$OF_STACK" != "$STORE_CLOUD" ]; then
    echo "Stack $STACK is of the cloud $OF_STACK, but the cloud given is $STORE_CLOUD" >&2; exit 1
  fi
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
}

# Sets the env of the objects: the global one, given by --env or --cdktf-env, else the one that the cdktf symlink points to.
resolve_store_env(){
  # The env and the cdktf dir are set by the command, which has already switched the cdktf symlink to
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
    echo "No env: give --env or --cdktf-env, or switch to one with cdktf-switch" >&2
    exit 1
  fi
}

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
    azure)
      # In a job of GitHub Actions, the token of the OIDC login lasts 5 minutes, and az needs a new one for the storage, which the
      # job may be first to use long after: log in again with a fresh one.
      if [ -n "${AZURE_OIDC_CLIENT_ID:-}" ] && [ -n "${ACTIONS_ID_TOKEN_REQUEST_URL:-}" ]; then
        bash "$AFON_ROOT/scripts/azure/login-oidc.sh" || echo "WARNING: Could not log in to Azure again: the store may refuse access" >&2
      fi
      az account show > /dev/null 2>&1 || fail "Not logged in to Azure: run azure-login" ;;
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
  local NAME="$1"
  case "$STORE_CLOUD" in
    aws)
      local ACCOUNT_ID
      ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text) \
        || fail "Failed to get the AWS account: is the aws CLI logged in?"
      BUCKET="$NAME-$ACCOUNT_ID"
      [ -z "$REGION" ] && REGION=$(aws configure get region 2>/dev/null || true)
      ;;
    azure)
      if [ -z "$ACCOUNT" ]; then
        ACCOUNT=$(config_property AZURE_STORAGE_ACCOUNT azure)
      fi
      if [ -z "$ACCOUNT" ]; then
        ACCOUNT="${STORE_ENV}afonstorageaccount"
        echo "No AZURE_STORAGE_ACCOUNT in the config of env $STORE_ENV: using the default $ACCOUNT, or give --account"
      fi
      BUCKET="$NAME"
      ;;
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
      BUCKET="$NAME-$STORE_PROJECT"
      ;;
  esac
  return 0
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

# Prints the objects with the prefix, one per line: their key, a tab and the time they last changed.
store_objects(){
  local PREFIX="$1"
  case "$STORE_CLOUD" in
    aws)
      aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$PREFIX" --query 'Contents[].[Key,LastModified]' --output json \
        | jq -r '.[]? | @tsv' ;;
    azure)
      az storage blob list --account-name "$ACCOUNT" --container-name "$BUCKET" --prefix "$PREFIX" \
        --auth-mode login --query '[].[name,properties.lastModified]' -o json | jq -r '.[] | @tsv' ;;
    gcp)
      # Nothing matching is not an error, whereas a refused access is.  The lines of the objects are: size, time, url.
      local OUT
      if ! OUT=$(gcloud storage ls --long --recursive "gs://$BUCKET/$PREFIX" --project "$STORE_PROJECT" 2>"$WORK_DIR/ls.err"); then
        grep -qiE "matched no objects|not found|404" "$WORK_DIR/ls.err" || { cat "$WORK_DIR/ls.err" >&2; return 1; }
        OUT=
      fi
      printf '%s\n' "$OUT" | awk -v b="gs://$BUCKET/" '$1 ~ /^[0-9]+$/ && index($3, b) == 1 { print substr($3, length(b) + 1) "\t" $2 }' ;;
  esac
}

# Prints the keys of the objects with the prefix.
store_keys(){
  store_objects "$1" | cut -f1
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
