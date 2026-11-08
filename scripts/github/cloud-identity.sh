#!/usr/bin/env bash
# Sets up the cloud side of the GitHub OIDC login of the smoke tests workflows: the identity that a job of the GitHub environment
# smoke-tests-<cloud> logs in as, with the trust rule that only that environment's token may use it, and the permissions to deploy the
# stack, read the config vault and use the cdktf store.  The GitHub side (the environment and its variables) is github-cloud-env.
#
# github-cloud-identity {create|show|delete} <aws|azure|gcp> [--repo <owner/repo>] [--name <name>] [--dry-run] [--force] [cloud options]
#
# Run it after <cloud>-login, as an admin of the TESTING cloud account (AWS account, Azure subscription or GCP project) that the stacks of the smoke tests are deployed to,
# never a production one.  'create' is idempotent:
#   aws    the IAM OIDC provider of GitHub (once per account); the role <name> trusted by the token with the subject below, with a 2-hour
#          maximum session; the managed policies of --deploy-role attached; and an inline policy to read the config vault (Secrets Manager
#          afon/*), use the cdktf store (the S3 bucket afon-cdktf-<account-id>) and write to the logs store (afon-logs-<account-id>)
#   azure  the app registration <name> and its service principal; the federated credential for the subject; the role assignments of
#          --deploy-role on the subscription; Key Vault Secrets User on the Key Vault AZURE_KEY_VAULT_NAME; Storage Blob Data Contributor on
#          the storage account AZURE_STORAGE_ACCOUNT
#   gcp    the APIs; the workload identity pool and GitHub provider, limited to the subject by an attribute condition; the service account
#          <name>, which that subject may impersonate; the roles of --deploy-role on the project; Secret Manager access on the project; and
#          Storage Object Admin on the bucket afon-cdktf-<project-id>, and Storage Object Creator on afon-logs-<project-id>, if they exist
# The subject is repo:<owner/repo>:environment:smoke-tests-<cloud> (with the ids of the owner and repo, if the repo uses them, which gh
# tells), the token claim that the trust rule is limited to.  At the end it
# prints the github-cloud-env command with the identifiers to set on the GitHub environment.
#
# 'show' reports the identity and its trust.  'delete' removes the identity (not the shared OIDC provider or workload identity pool) and
# needs --force.
#
# The deploy permissions default to those of a full stack in a testing account, which is broad (see --deploy-role): AdministratorAccess on aws,
# Contributor and User Access Administrator on azure (the stack creates role assignments), Owner on gcp.  Narrow them with --deploy-role,
# which replaces the default and can be repeated: a managed policy ARN on aws, a role name on azure, a role id on gcp.
#
# Options: --repo, --name (default afon-smoke-tests), --config-env <env> (limits the vault access to the snapshots of the env, else all),
#          --deploy-role (as above), --dry-run (reports what would be written; the checks that read the cloud still run), --force
#   gcp:   --pool <name> (default github)  --provider <name> (default github)
#
# It acts on the account or subscription that <cloud>-login logged in to (aws checks it against AWS_ACCOUNT_ID of the active config), and
# reads the rest from the active config: GCP_PROJECT_ID, and on Azure AZURE_KEY_VAULT_NAME and AZURE_STORAGE_ACCOUNT.  To target others,
# switch the env (--env <env>) and log in again.
#
# The repo is --repo, else env variable GH_REPO, else emrys-health/afon.  See docs/github_workflows.md, section GitHub as the identity
# provider, for what each step is.
set -euo pipefail

DEFAULT_REPO="emrys-health/afon"
ENV_PREFIX="smoke-tests-"
DEFAULT_NAME="afon-smoke-tests"
GITHUB_ISSUER="https://token.actions.githubusercontent.com"
DOCS="docs/github_workflows.md#github-as-the-identity-provider"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {create|show|delete} {aws|azure|gcp} [--repo <owner/repo>] [--name <name>] [--config-env <env>]"
  echo "          [--deploy-role <role>]... [--dry-run] [--force] [cloud options]"
  echo "actions:  create :  creates or updates the identity, its trust for ${ENV_PREFIX}<cloud> and its permissions"
  echo "          show   :  reports the identity and its trust"
  echo "          delete :  removes the identity.  Needs --force"
  echo "repo:         the GitHub repo whose environment is trusted.  Defaults to env variable GH_REPO, else $DEFAULT_REPO."
  echo "name:         the name of the role, app registration or service account.  Defaults to $DEFAULT_NAME."
  echo "config-env:   limit the access to the config vault to the snapshots of this env.  Defaults to all (aws)."
  echo "deploy-role:  what the identity may deploy with, repeatable, replacing the default: aws managed policy ARNs"
  echo "              (default arn:aws:iam::aws:policy/AdministratorAccess), azure role names (default Contributor and"
  echo "              User Access Administrator), gcp role ids (default roles/owner)."
  echo "dry-run:      the flag to report what would be written, without writing anything to the cloud."
  echo "force:        the flag to let delete remove the identity."
  echo "gcp:          [--pool <name>] [--provider <name>]"
  echo "It acts on the account or subscription of the login, and reads the project, Key Vault and storage account from the active config."
  echo "See $DOCS."
}

die(){
  echo "$1" >&2
  exit 1
}

# Runs the command, or says it would, with --dry-run.
write(){
  if [ "$DRY_RUN" == "true" ]; then
    echo "would run: $*"
  else
    "$@"
  fi
}

# Reports what was done, marked as not done with --dry-run.
say(){
  if [ "$DRY_RUN" == "true" ]; then echo "(dry-run) $*"; else echo "$*"; fi
}

need(){
  command -v "$1" > /dev/null 2>&1 || die "Requires $1 on the PATH"
}

# Prints the stdout of the command, or nothing if it fails.
quiet(){
  "$@" 2> /dev/null || true
}

# Prints the value of a property of the active config (the config dir of AFON_HOME, which the env switched to), which may be set in terms
# of others, eg ${ENV_NAME}-afon-key-vault.  Prints nothing if there is none.
config_property(){
  local PROPERTY="$1" CLOUD_NAME="$2" FILE
  (
    set +eu
    set -a
    for FILE in afon.properties "afon-$CLOUD_NAME.properties" ; do {
      [ -f "${AFON_HOME:-}/config/$FILE" ] && source "${AFON_HOME:-}/config/$FILE" > /dev/null 2>&1
    } done
    printf '%s' "${!PROPERTY:-}"
  )
}

# The options of github-cloud-env that differ from its defaults (none, usually), for the command that the create prints.
next_options(){
  [ "$REPO" == "${GH_REPO:-$DEFAULT_REPO}" ] || echo -n " --repo $REPO"
  [ "$NAME" == "$DEFAULT_NAME" ] || echo -n " --name $NAME"
  if [ "$CLOUD" == "gcp" ]; then
    [ "$POOL" == "github" ] || echo -n " --pool $POOL"
    [ "$PROVIDER" == "github" ] || echo -n " --provider $PROVIDER"
  fi
  echo
}

# Prints the start of the sub claim of the tokens of the repo's jobs.  It is repo:<owner>/<repo> by default, but a repo can use the immutable
# ids of its owner and name instead, eg repo:<owner>@<owner-id>/<repo>@<repo-id>, as new repos do: the trust must be limited to what
# the token has, which GitHub tells.
subject_prefix(){
  local TEMPLATE
  command -v gh > /dev/null 2>&1 || die "Requires gh on the PATH, to read the subject claim of the repo"
  TEMPLATE="$(gh api "repos/$REPO/actions/oidc/customization/sub" 2> /dev/null)" \
    || die "Cannot read the OIDC subject template of $REPO: is gh logged in, as an admin of the repo?"
  if [ "$(jq -r '.use_default // true' <<< "$TEMPLATE")" != "true" ]; then
    die "$REPO customizes the claims of its OIDC subject (include_claim_keys): not supported, as the trust would not match its tokens"
  elif [ "$(jq -r '.use_immutable_subject // false' <<< "$TEMPLATE")" == "true" ]; then
    jq -r '.sub_claim_prefix' <<< "$TEMPLATE"
  else
    echo "repo:$REPO"
  fi
}

# --- AWS ---------------------------------------------------------------------------------------------------------------------------

# The account of the login, which must be that of the config, if it has one.
aws_account(){
  local ACCOUNT CONFIGURED
  ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2> /dev/null)" || die "aws is not logged in: no usable credentials"
  CONFIGURED="$(config_property AWS_ACCOUNT_ID aws)"
  [ -z "$CONFIGURED" ] || [ "$CONFIGURED" == "$ACCOUNT" ] \
    || die "The aws login is of the account $ACCOUNT, but the config is of $CONFIGURED: run aws-login"
  echo "$ACCOUNT"
}

aws_create(){
  local ACCOUNT PROVIDER_ARN TRUST ACCESS POLICY SECRET_SCOPE BUCKET LOGS_BUCKET
  ACCOUNT="$(aws_account)"
  PROVIDER_ARN="arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com"
  echo "AWS account $ACCOUNT"

  if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$PROVIDER_ARN" > /dev/null 2>&1; then
    echo "The OIDC provider of GitHub exists"
  else
    write aws iam create-open-id-connect-provider --url "$GITHUB_ISSUER" --client-id-list sts.amazonaws.com > /dev/null
    say "Added the OIDC provider of GitHub"
  fi

  TRUST="$(jq -n --arg provider "$PROVIDER_ARN" --arg sub "$SUBJECT" '{
    Version: "2012-10-17",
    Statement: [{
      Effect: "Allow",
      Principal: {Federated: $provider},
      Action: "sts:AssumeRoleWithWebIdentity",
      Condition: {StringEquals: {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": $sub}}}]}')"
  if aws iam get-role --role-name "$NAME" > /dev/null 2>&1; then
    write aws iam update-assume-role-policy --role-name "$NAME" --policy-document "$TRUST"
    write aws iam update-role --role-name "$NAME" --max-session-duration 7200 > /dev/null
    say "Updated the trust of the role $NAME to $SUBJECT"
  else
    write aws iam create-role --role-name "$NAME" --assume-role-policy-document "$TRUST" --max-session-duration 7200 \
      --description "Smoke tests of $REPO, trusted by the GitHub environment $ENV_NAME" > /dev/null
    say "Created the role $NAME, trusting $SUBJECT"
  fi

  for POLICY in "${DEPLOY_ROLES[@]}"; do
    write aws iam attach-role-policy --role-name "$NAME" --policy-arn "$POLICY"
    say "Attached $POLICY"
  done

  SECRET_SCOPE="afon/${CONFIG_ENV:-*}/*"
  BUCKET="afon-cdktf-$ACCOUNT"
  LOGS_BUCKET="afon-logs-$ACCOUNT"
  # ListSecrets cannot be limited to a resource, and config-vault pull finds the snapshot by its tag with it.  The logs bucket is only
  # written to: the job stores its logs there, and an admin reads them.
  ACCESS="$(jq -n --arg secrets "arn:aws:secretsmanager:*:$ACCOUNT:secret:$SECRET_SCOPE" --arg bucket "arn:aws:s3:::$BUCKET" \
              --arg logs "arn:aws:s3:::$LOGS_BUCKET" '{
    Version: "2012-10-17",
    Statement: [
      {Effect: "Allow", Action: ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"], Resource: $secrets},
      {Effect: "Allow", Action: "secretsmanager:ListSecrets", Resource: "*"},
      {Effect: "Allow", Action: ["s3:ListBucket", "s3:GetBucketLocation"], Resource: $bucket},
      {Effect: "Allow", Action: ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource: ($bucket + "/*")},
      {Effect: "Allow", Action: ["s3:ListBucket", "s3:GetBucketLocation"], Resource: $logs},
      {Effect: "Allow", Action: "s3:PutObject", Resource: ($logs + "/*")}]}')"
  write aws iam put-role-policy --role-name "$NAME" --policy-name "$NAME-access" --policy-document "$ACCESS"
  say "Set the inline policy $NAME-access: the vault ($SECRET_SCOPE), the cdktf store ($BUCKET) and the logs ($LOGS_BUCKET, write only)"

  echo ""
  echo "Next, set the GitHub environment:"
  echo "    github-cloud-env create aws$(next_options)"
}

aws_show(){
  local ACCOUNT
  ACCOUNT="$(aws_account)"
  echo "AWS account $ACCOUNT"
  aws iam get-open-id-connect-provider --open-id-connect-provider-arn "arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com" \
    --query '{url: Url, clients: ClientIDList}' --output json || echo "No OIDC provider of GitHub"
  aws iam get-role --role-name "$NAME" --query 'Role.{arn: Arn, maxSession: MaxSessionDuration, trust: AssumeRolePolicyDocument}' --output json \
    || die "No role $NAME"
  say "Attached policies:"
  aws iam list-attached-role-policies --role-name "$NAME" --query 'AttachedPolicies[].PolicyArn' --output text
  echo "Inline policies:"
  aws iam list-role-policies --role-name "$NAME" --query 'PolicyNames' --output text
}

aws_delete(){
  local POLICY
  aws iam get-role --role-name "$NAME" > /dev/null 2>&1 || die "No role $NAME"
  for POLICY in $(aws iam list-attached-role-policies --role-name "$NAME" --query 'AttachedPolicies[].PolicyArn' --output text); do
    write aws iam detach-role-policy --role-name "$NAME" --policy-arn "$POLICY"
  done
  for POLICY in $(aws iam list-role-policies --role-name "$NAME" --query 'PolicyNames' --output text); do
    write aws iam delete-role-policy --role-name "$NAME" --policy-name "$POLICY"
  done
  write aws iam delete-role --role-name "$NAME"
  say "Deleted the role $NAME.  The OIDC provider of GitHub is left, as other roles may use it."
}

# --- Azure -------------------------------------------------------------------------------------------------------------------------

# Prints the object id of the service principal of the app, or nothing.
azure_sp_id(){
  quiet az ad sp show --id "$1" --query id -o tsv
}

azure_assign(){
  local ROLE="$1" SCOPE="$2" OBJECT_ID="$3" COUNT
  COUNT="$(quiet az role assignment list --assignee "$OBJECT_ID" --role "$ROLE" --scope "$SCOPE" --query 'length(@)' -o tsv)"
  if [ "${COUNT:-0}" -gt 0 ] 2> /dev/null; then
    echo "$ROLE is assigned on $SCOPE"
  else
    write az role assignment create --assignee-object-id "$OBJECT_ID" --assignee-principal-type ServicePrincipal \
      --role "$ROLE" --scope "$SCOPE" > /dev/null
    say "Assigned $ROLE on $SCOPE"
  fi
}

azure_create(){
  local TENANT APP_ID SP_ID PARAMS CRED ROLE SCOPE
  az account show > /dev/null 2>&1 || die "az is not logged in: run az login"
  SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
  TENANT="$(az account show --query tenantId -o tsv)"
  echo "Azure subscription $SUBSCRIPTION_ID, tenant $TENANT"

  APP_ID="$(quiet az ad app list --display-name "$NAME" --query '[0].appId' -o tsv)"
  if [ -n "$APP_ID" ]; then
    echo "The app registration $NAME exists: $APP_ID"
  elif [ "$DRY_RUN" == "true" ]; then
    echo "would run: az ad app create --display-name $NAME"
    APP_ID="<app-id>"
  else
    APP_ID="$(az ad app create --display-name "$NAME" --query appId -o tsv)"
    say "Created the app registration $NAME: $APP_ID"
  fi

  SP_ID=
  [ "$APP_ID" == "<app-id>" ] || SP_ID="$(azure_sp_id "$APP_ID")"
  if [ -n "$SP_ID" ]; then
    echo "The service principal exists: $SP_ID"
  elif [ "$DRY_RUN" == "true" ]; then
    echo "would run: az ad sp create --id $APP_ID"
    SP_ID="<service-principal-id>"
  else
    SP_ID="$(az ad sp create --id "$APP_ID" --query id -o tsv)"
    say "Created the service principal: $SP_ID"
  fi

  CRED="github-$ENV_NAME"
  PARAMS="$(jq -n --arg name "$CRED" --arg sub "$SUBJECT" --arg issuer "$GITHUB_ISSUER" \
    '{name: $name, issuer: $issuer, subject: $sub, audiences: ["api://AzureADTokenExchange"]}')"
  if [ "$APP_ID" != "<app-id>" ] && [ -n "$(quiet az ad app federated-credential list --id "$APP_ID" --query "[?name=='$CRED'].name" -o tsv)" ]; then
    write az ad app federated-credential update --id "$APP_ID" --federated-credential-id "$CRED" --parameters "$PARAMS"
    say "Updated the federated credential $CRED to $SUBJECT"
  else
    write az ad app federated-credential create --id "$APP_ID" --parameters "$PARAMS" > /dev/null
    say "Created the federated credential $CRED for $SUBJECT"
  fi

  for ROLE in "${DEPLOY_ROLES[@]}"; do
    azure_assign "$ROLE" "/subscriptions/$SUBSCRIPTION_ID" "$SP_ID"
  done
  if [ -n "$KEY_VAULT_NAME" ]; then
    SCOPE="$(az keyvault show --name "$KEY_VAULT_NAME" --query id -o tsv)" || die "No Key Vault $KEY_VAULT_NAME"
    azure_assign "Key Vault Secrets User" "$SCOPE" "$SP_ID"
  else
    echo "No AZURE_KEY_VAULT_NAME in the config: the identity cannot read the config vault yet"
  fi
  if [ -n "$STORAGE_ACCOUNT" ]; then
    SCOPE="$(az storage account show --name "$STORAGE_ACCOUNT" --query id -o tsv)" || die "No storage account $STORAGE_ACCOUNT"
    azure_assign "Storage Blob Data Contributor" "$SCOPE" "$SP_ID"
  else
    echo "No AZURE_STORAGE_ACCOUNT in the config: the identity cannot use the cdktf store yet"
  fi

  echo ""
  echo "Next, set the GitHub environment:"
  echo "    github-cloud-env create azure$(next_options)"
}

azure_show(){
  local APP_ID SP_ID
  APP_ID="$(quiet az ad app list --display-name "$NAME" --query '[0].appId' -o tsv)"
  [ -n "$APP_ID" ] || die "No app registration $NAME"
  echo "App registration $NAME: client id $APP_ID, tenant $(az account show --query tenantId -o tsv)"
  echo "Federated credentials:"
  az ad app federated-credential list --id "$APP_ID" --query '[].{name: name, issuer: issuer, subject: subject, audiences: audiences}' -o json
  SP_ID="$(azure_sp_id "$APP_ID")"
  echo "Role assignments of the service principal ${SP_ID:-none}:"
  [ -z "$SP_ID" ] || az role assignment list --all --assignee "$SP_ID" --query '[].{role: roleDefinitionName, scope: scope}' -o table
}

azure_delete(){
  local APP_ID
  APP_ID="$(quiet az ad app list --display-name "$NAME" --query '[0].appId' -o tsv)"
  [ -n "$APP_ID" ] || die "No app registration $NAME"
  # Deleting the app removes its service principal's role assignments on the next cleanup, but they are removed here to be sure.
  local ID
  for ID in $(quiet az role assignment list --all --assignee "$(azure_sp_id "$APP_ID")" --query '[].id' -o tsv); do
    write az role assignment delete --ids "$ID"
  done
  write az ad app delete --id "$APP_ID"
  say "Deleted the app registration $NAME and its role assignments"
}

# --- GCP ---------------------------------------------------------------------------------------------------------------------------

gcp_project(){
  [ -n "$PROJECT_ID" ] || die "No GCP_PROJECT_ID in the config: switch to the env of the project (--env <env>)"
  PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)' 2> /dev/null)" \
    || die "Cannot read the project $PROJECT_ID: is gcloud logged in?"
  SA_EMAIL="$NAME@$PROJECT_ID.iam.gserviceaccount.com"
  POOL_PATH="projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$POOL"
  MEMBER="principal://iam.googleapis.com/$POOL_PATH/subject/$SUBJECT"
}

# Enables the APIs that the login and the stacks need and that are not yet, which takes the permission to enable services on the project,
# eg Owner or Service Usage Admin: those that are enabled need nothing.
gcp_enable_apis(){
  local APIS=(iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com \
              secretmanager.googleapis.com storage.googleapis.com)
  local ENABLED API MISSING=()
  ENABLED="$(quiet gcloud services list --enabled --project "$PROJECT_ID" --format='value(config.name)')"
  for API in "${APIS[@]}"; do
    printf '%s\n' "$ENABLED" | grep -qx "$API" || MISSING+=("$API")
  done
  if [ ${#MISSING[@]} -eq 0 ]; then
    echo "The APIs are enabled"
  elif write gcloud services enable "${MISSING[@]}" --project "$PROJECT_ID"; then
    say "Enabled ${MISSING[*]}"
  else
    die "Cannot enable ${MISSING[*]}: ask an Owner of the project (or a Service Usage Admin) to run 'gcloud services enable ${MISSING[*]} --project $PROJECT_ID', then run this again"
  fi
}

gcp_bind(){
  local ROLE="$1"
  write gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$SA_EMAIL" --role "$ROLE" --condition=None --quiet > /dev/null
  say "Granted $ROLE on the project"
}

gcp_create(){
  local ROLE BUCKET LOGS_BUCKET
  gcp_project
  echo "GCP project $PROJECT_ID ($PROJECT_NUMBER)"

  gcp_enable_apis

  if gcloud iam workload-identity-pools describe "$POOL" --location=global --project "$PROJECT_ID" > /dev/null 2>&1; then
    echo "The workload identity pool $POOL exists"
  else
    write gcloud iam workload-identity-pools create "$POOL" --location=global --project "$PROJECT_ID" --display-name="GitHub" > /dev/null
    say "Created the workload identity pool $POOL"
  fi

  local VERB=create-oidc
  if gcloud iam workload-identity-pools providers describe "$PROVIDER" --workload-identity-pool="$POOL" --location=global \
       --project "$PROJECT_ID" > /dev/null 2>&1; then
    VERB=update-oidc
  fi
  write gcloud iam workload-identity-pools providers "$VERB" "$PROVIDER" --workload-identity-pool="$POOL" --location=global \
    --project "$PROJECT_ID" --issuer-uri="$GITHUB_ISSUER" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition="assertion.sub=='$SUBJECT'" > /dev/null
  say "Set the provider $PROVIDER (${VERB%-oidc}d), limited to $SUBJECT"

  if gcloud iam service-accounts describe "$SA_EMAIL" --project "$PROJECT_ID" > /dev/null 2>&1; then
    echo "The service account $SA_EMAIL exists"
  else
    write gcloud iam service-accounts create "$NAME" --project "$PROJECT_ID" --display-name "Smoke tests of $REPO" > /dev/null
    say "Created the service account $SA_EMAIL"
  fi
  write gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" --project "$PROJECT_ID" \
    --role roles/iam.workloadIdentityUser --member "$MEMBER" --quiet > /dev/null
  say "$SUBJECT may impersonate $SA_EMAIL"

  for ROLE in "${DEPLOY_ROLES[@]}" roles/secretmanager.secretAccessor roles/secretmanager.viewer; do
    gcp_bind "$ROLE"
  done

  BUCKET="afon-cdktf-$PROJECT_ID"
  if gcloud storage buckets describe "gs://$BUCKET" > /dev/null 2>&1; then
    write gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member "serviceAccount:$SA_EMAIL" --role roles/storage.objectAdmin > /dev/null
    say "Granted roles/storage.objectAdmin on gs://$BUCKET"
  else
    echo "The cdktf store gs://$BUCKET does not exist yet: create it with a first cdktf-store push, then run this again"
  fi

  # The logs are only written to: the job stores its logs there, and an admin reads them.  legacyBucketReader is to find the bucket.
  LOGS_BUCKET="afon-logs-$PROJECT_ID"
  if gcloud storage buckets describe "gs://$LOGS_BUCKET" > /dev/null 2>&1; then
    for ROLE in roles/storage.objectCreator roles/storage.legacyBucketReader; do
      write gcloud storage buckets add-iam-policy-binding "gs://$LOGS_BUCKET" --member "serviceAccount:$SA_EMAIL" --role "$ROLE" > /dev/null
      say "Granted $ROLE on gs://$LOGS_BUCKET"
    done
  else
    echo "The logs store gs://$LOGS_BUCKET does not exist yet: create it with a first logs-store push, then run this again"
  fi

  echo ""
  echo "Next, set the GitHub environment:"
  echo "    github-cloud-env create gcp$(next_options)"
}

gcp_show(){
  gcp_project
  echo "GCP project $PROJECT_ID ($PROJECT_NUMBER)"
  gcloud iam workload-identity-pools providers describe "$PROVIDER" --workload-identity-pool="$POOL" --location=global --project "$PROJECT_ID" \
    --format='yaml(name,state,oidc.issuerUri,attributeCondition,attributeMapping)' || echo "No provider $PROVIDER in pool $POOL"
  echo "Workload identity provider: $POOL_PATH/providers/$PROVIDER"
  gcloud iam service-accounts get-iam-policy "$SA_EMAIL" --project "$PROJECT_ID" --format=yaml || die "No service account $SA_EMAIL"
  echo "Roles of $SA_EMAIL on the project:"
  gcloud projects get-iam-policy "$PROJECT_ID" --flatten='bindings[].members' --filter="bindings.members:serviceAccount:$SA_EMAIL" \
    --format='value(bindings.role)'
}

gcp_delete(){
  local ROLE
  gcp_project
  for ROLE in $(quiet gcloud projects get-iam-policy "$PROJECT_ID" --flatten='bindings[].members' \
                  --filter="bindings.members:serviceAccount:$SA_EMAIL" --format='value(bindings.role)'); do
    write gcloud projects remove-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$SA_EMAIL" --role "$ROLE" --condition=None --quiet > /dev/null
  done
  write gcloud iam service-accounts delete "$SA_EMAIL" --project "$PROJECT_ID" --quiet
  write gcloud iam workload-identity-pools providers delete "$PROVIDER" --workload-identity-pool="$POOL" --location=global --project "$PROJECT_ID" --quiet
  say "Deleted the service account and the provider $PROVIDER.  The pool $POOL is left, as other providers may use it."
}

# --- main --------------------------------------------------------------------------------------------------------------------------

ACTION=
case "${1:-}" in
  create|show|delete) ACTION="$1"; shift ;;
  -h|--help|"") usage; exit 0 ;;
  *) usage >&2; die "Unknown action '$1': it must be create, show or delete" ;;
esac
CLOUD="${1:-}"
case "$CLOUD" in
  aws|azure|gcp) shift ;;
  *) usage >&2; die "Unknown cloud '$CLOUD': it must be aws, azure or gcp" ;;
esac

REPO=; NAME="$DEFAULT_NAME"; CONFIG_ENV=; DEPLOY_ROLES=(); DRY_RUN=false; FORCE=false
KEY_VAULT_NAME=; STORAGE_ACCOUNT=; PROJECT_ID=; POOL=github; PROVIDER=github
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift; continue ;;
    --force) FORCE=true; shift; continue ;;
    -h|--help) usage; exit 0 ;;
  esac
  [ $# -ge 2 ] || die "Option $1 requires a value"
  case "$1" in
    --repo) REPO="$2" ;;
    --name) NAME="$2" ;;
    --config-env) CONFIG_ENV="$2" ;;
    --deploy-role) DEPLOY_ROLES+=("$2") ;;
    --pool) POOL="$2" ;;
    --provider) PROVIDER="$2" ;;
    *) usage >&2; die "Unknown option $1" ;;
  esac
  shift 2
done

[ -n "$REPO" ] || REPO="${GH_REPO:-$DEFAULT_REPO}"
[[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "Invalid repo $REPO: expected <owner>/<repo>"
[[ "$NAME" =~ ^[A-Za-z0-9_+=,.@-]+$ ]] || die "Invalid name $NAME"
ENV_NAME="$ENV_PREFIX$CLOUD"
SUBJECT="$(subject_prefix):environment:$ENV_NAME"

if [ ${#DEPLOY_ROLES[@]} -eq 0 ]; then
  case "$CLOUD" in
    aws) DEPLOY_ROLES=("arn:aws:iam::aws:policy/AdministratorAccess") ;;
    azure) DEPLOY_ROLES=("Contributor" "User Access Administrator") ;;
    gcp) DEPLOY_ROLES=("roles/owner") ;;
  esac
fi

# What the cloud is told besides the login is that of the active config.
case "$CLOUD" in
  azure)
    KEY_VAULT_NAME="$(config_property AZURE_KEY_VAULT_NAME azure)"
    STORAGE_ACCOUNT="$(config_property AZURE_STORAGE_ACCOUNT azure)" ;;
  gcp)
    PROJECT_ID="$(config_property GCP_PROJECT_ID gcp)" ;;
esac

need jq
case "$CLOUD" in aws) need aws ;; azure) need az ;; gcp) need gcloud ;; esac
echo "Cloud $CLOUD, identity $NAME, trusted subject $SUBJECT"
[ "$ACTION" != "delete" ] || [ "$FORCE" == "true" ] || die "Deleting the identity $NAME needs --force"
"${CLOUD}_${ACTION}"
