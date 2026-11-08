#!/usr/bin/env bash
# Sets up the GitHub side of the OIDC login of the smoke tests workflows to a cloud: the GitHub environment smoke-tests-<cloud>
# that the cloud's trust is limited to, its deployment branches, and the variables that the workflows log in with.
#
# github-cloud-env {setup|show|delete} <aws|azure|gcp> [--repo <owner/repo>] [--branch <branch>] [--reviewer <login>]... [--name <name>] [--dry-run] [--force]
#
# 'setup' is idempotent, and:
#   1. creates the environment smoke-tests-<cloud>, or updates it: deployments limited to the branch (main by default, --branch), admins
#      not able to bypass that, and the required reviewers if --reviewer is given (not changed if it is not);
#   2. sets the variables of the cloud on the environment (below), and fails, setting nothing, if any is missing;
#   3. sets the repository variables SMOKE_TESTS_CONFIG_ENV and SMOKE_TESTS_CONFIG_TAG if --config-env and --config-tag are given;
#   4. prints the subject that the trust rule of the cloud must be limited to, repo:<owner/repo>:environment:smoke-tests-<cloud>.
# It does not touch the cloud: the role, app registration or workload identity pool are created cloud-side, see docs/github_workflows.md,
# section GitHub as the identity provider.
#
# 'show' reports the environment, its branch rules, reviewers and variables, and the repository variables of the workflows.
# 'delete' removes the environment and its variables, and needs --force.
#
# The variables of the environment are not given as options: they are read from the active config (afon-cli.sh --env <env> to switch), or
# found by the name of the identity of github-cloud-identity (--name, default afon-smoke-tests):
#   aws    AWS_ROLE_ARN   the role --name in the account AWS_ACCOUNT_ID of the config
#          AWS_REGION     AWS_REGION of the config
#   azure  AZURE_CLIENT_ID  the app registration --name        AZURE_TENANT_ID  AZURE_TENANT_ID of the config
#          AZURE_SUBSCRIPTION_ID  the subscription of the az login        AZURE_KEY_VAULT_NAME  AZURE_KEY_VAULT_NAME of the config, if any
#   gcp    GCP_PROJECT_ID  GCP_PROJECT_ID of the config
#          GCP_WORKLOAD_IDENTITY_PROVIDER  projects/<number>/locations/global/workloadIdentityPools/<--pool, github>/providers/<--provider,
#                                          github>, the number being that of the project
#          GCP_SERVICE_ACCOUNT  <--name>@<project-id>.iam.gserviceaccount.com
#
# Requires the gh CLI, logged in (gh auth login, or GH_TOKEN) with the 'repo' scope, as an admin of the repo, which creating an
# environment needs; and jq.  Using environments in a private repo requires a paid plan.
#
# The repo is --repo, else env variable GH_REPO, else emrys-health/afon.  It does not depend on the current dir.
set -euo pipefail

DEFAULT_REPO="emrys-health/afon"
ENV_PREFIX="smoke-tests-"
DOCS="docs/github_workflows.md#github-as-the-identity-provider"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {setup|show|delete} {aws|azure|gcp} [--repo <owner/repo>] [--branch <branch>] [--reviewer <login>]..."
  echo "          [--config-env <env> --config-tag <tag>] [--name <name>] [--dry-run] [--force]"
  echo "actions:  setup  :  creates or updates the environment ${ENV_PREFIX}<cloud> and sets its variables"
  echo "          show   :  reports the environment, its branch rules and variables"
  echo "          delete :  removes the environment and its variables.  Needs --force"
  echo "repo:       the target repo.  Defaults to env variable GH_REPO, if set, else to $DEFAULT_REPO."
  echo "branch:     the only branch that may deploy to the environment.  Defaults to main."
  echo "reviewer:   a GitHub login that must approve each run, repeatable (up to 6).  Without it the reviewers are left as they are."
  echo "config-env, config-tag:  set the repository variables SMOKE_TESTS_CONFIG_ENV and SMOKE_TESTS_CONFIG_TAG, the config snapshot"
  echo "            that the workflows pull when triggered by a build.  Both or neither."
  echo "dry-run:    the flag to report what would be done, without writing anything to GitHub."
  echo "force:      the flag to let delete remove the environment."
  echo "name:       the name of the identity of github-cloud-identity.  Defaults to afon-smoke-tests."
  echo "gcp:        [--pool <name>] [--provider <name>]"
  echo "The variables of the cloud are read from the active config, and from the login of azure and gcp."
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

check_tools(){
  local TOOL
  for TOOL in gh jq; do
    command -v "$TOOL" > /dev/null 2>&1 || die "Requires $TOOL on the PATH"
  done
  gh auth status > /dev/null 2>&1 || die "gh is not logged in: run gh auth login, or set GH_TOKEN"
}

# Prints the output of the command, or nothing if it is not installed or fails.
try(){
  command -v "$1" > /dev/null 2>&1 || return 0
  "$@" 2> /dev/null || true
}

# Prints the value of a property of the active config (the config dir of AFON_HOME, which the env switched to), which may be set in terms
# of others, from the afon and the secret files of the cloud (AZURE_TENANT_ID is in the latter).  Prints nothing if there is none.
config_property(){
  local PROPERTY="$1" CLOUD_NAME="$2" FILE
  (
    set +eu
    set -a
    for FILE in afon.properties "afon-$CLOUD_NAME.properties" "secret-$CLOUD_NAME.properties" ; do {
      [ -f "${AFON_HOME:-}/config/$FILE" ] && source "${AFON_HOME:-}/config/$FILE" > /dev/null 2>&1
    } done
    printf '%s' "${!PROPERTY:-}"
  )
}

# Adds NAME=value to VARIABLES if the value is not empty.
add_variable(){
  if [ -n "$2" ]; then
    VARIABLES+=("$1=$2")
  fi
}

# Fills VARIABLES with those of the cloud, and fails if any required one is missing.
collect_variables(){
  local MISSING=() ACCOUNT
  local ROLE_ARN REGION CLIENT_ID TENANT_ID SUBSCRIPTION_ID KEY_VAULT_NAME PROJECT_ID PROJECT_NUMBER WIF_PROVIDER SERVICE_ACCOUNT
  VARIABLES=()
  case "$CLOUD" in
    aws)
      REGION="$(config_property AWS_REGION aws)"
      ACCOUNT="$(config_property AWS_ACCOUNT_ID aws)"
      [ -n "$REGION" ] || MISSING+=("AWS_REGION")
      [ -n "$ACCOUNT" ] || MISSING+=("AWS_ACCOUNT_ID")
      if [ -n "$ACCOUNT" ]; then
        ROLE_ARN="arn:aws:iam::$ACCOUNT:role/$NAME"
        [[ "$ROLE_ARN" =~ ^arn:aws:iam::[0-9]{12}:role/.+ ]] || die "Invalid AWS_ACCOUNT_ID $ACCOUNT: expected 12 digits"
        add_variable AWS_ROLE_ARN "$ROLE_ARN"
      fi
      add_variable AWS_REGION "$REGION"
      ;;
    azure)
      CLIENT_ID="$(try az ad app list --display-name "$NAME" --query '[0].appId' -o tsv)"
      TENANT_ID="$(config_property AZURE_TENANT_ID azure)"
      KEY_VAULT_NAME="$(config_property AZURE_KEY_VAULT_NAME azure)"
      SUBSCRIPTION_ID="$(try az account show --query id -o tsv)"
      [ -n "$CLIENT_ID" ] || MISSING+=("the app registration $NAME (run github-cloud-identity setup azure, logged in with azure-login)")
      [ -n "$TENANT_ID" ] || MISSING+=("AZURE_TENANT_ID")
      [ -n "$SUBSCRIPTION_ID" ] || MISSING+=("the subscription of the az login (run azure-login)")
      add_variable AZURE_CLIENT_ID "$CLIENT_ID"
      add_variable AZURE_TENANT_ID "$TENANT_ID"
      add_variable AZURE_SUBSCRIPTION_ID "$SUBSCRIPTION_ID"
      add_variable AZURE_KEY_VAULT_NAME "$KEY_VAULT_NAME"
      ;;
    gcp)
      PROJECT_ID="$(config_property GCP_PROJECT_ID gcp)"
      [ -n "$PROJECT_ID" ] || MISSING+=("GCP_PROJECT_ID")
      if [ -n "$PROJECT_ID" ]; then
        PROJECT_NUMBER="$(try gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
        [ -n "$PROJECT_NUMBER" ] || MISSING+=("the number of the project $PROJECT_ID (run gcp-login)")
      fi
      if [ -n "$PROJECT_NUMBER" ]; then
        WIF_PROVIDER="projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$POOL/providers/$PROVIDER"
      fi
      [ -z "$PROJECT_ID" ] || SERVICE_ACCOUNT="$NAME@$PROJECT_ID.iam.gserviceaccount.com"
      add_variable GCP_PROJECT_ID "$PROJECT_ID"
      add_variable GCP_WORKLOAD_IDENTITY_PROVIDER "$WIF_PROVIDER"
      add_variable GCP_SERVICE_ACCOUNT "$SERVICE_ACCOUNT"
      ;;
  esac
  if [ ${#MISSING[@]} -gt 0 ]; then
    die "Missing for $CLOUD: ${MISSING[*]}.  They come from the active config (afon-cli.sh --env <env>) or the login; nothing was set."
  fi
}

# Creates or updates the environment: the branch it deploys from, and the reviewers if given.
put_environment(){
  local BODY LOGIN ID IDS="[]"
  for LOGIN in ${REVIEWERS[@]+"${REVIEWERS[@]}"}; do
    ID="$(gh api "users/$LOGIN" --jq .id)" || die "No GitHub user $LOGIN"
    IDS="$(jq -c --argjson id "$ID" '. + [{type: "User", id: $id}]' <<< "$IDS")"
  done
  # reviewers is left out without --reviewer, as sending [] would remove the ones set by hand.
  BODY="$(jq -n --argjson ids "$IDS" --argjson withReviewers "$([ ${#REVIEWERS[@]} -gt 0 ] && echo true || echo false)" '
    {deployment_branch_policy: {protected_branches: false, custom_branch_policies: true}, can_admins_bypass: false}
    + (if $withReviewers then {reviewers: $ids} else {} end)')"
  local WHO=""
  [ ${#REVIEWERS[@]} -eq 0 ] || WHO=", reviewers ${REVIEWERS[*]}"
  echo "Environment $ENV_NAME of $REPO: deployments from $BRANCH only, admins cannot bypass$WHO"
  if [ "$DRY_RUN" == "true" ]; then
    echo "would run: gh api --method PUT repos/$REPO/environments/$ENV_NAME  <<< $BODY"
  else
    gh api --method PUT "repos/$REPO/environments/$ENV_NAME" --input - <<< "$BODY" > /dev/null
  fi
}

# Limits the deployments of the environment to the branch, and reports any other rule.
put_branch_policy(){
  local EXISTING
  if [ "$DRY_RUN" == "true" ]; then
    echo "would add the deployment branch $BRANCH to $ENV_NAME, if not there"
    return
  fi
  EXISTING="$(gh api "repos/$REPO/environments/$ENV_NAME/deployment-branch-policies" --jq '.branch_policies[].name')"
  if ! grep -qx -- "$BRANCH" <<< "$EXISTING"; then
    gh api --method POST "repos/$REPO/environments/$ENV_NAME/deployment-branch-policies" -f "name=$BRANCH" -f type=branch > /dev/null
    echo "Added the deployment branch $BRANCH"
  fi
  EXISTING="$(grep -vx -- "$BRANCH" <<< "$EXISTING" || true)"
  if [ -n "$EXISTING" ]; then
    echo "WARNING: $ENV_NAME also allows deployments from: $(echo "$EXISTING" | tr '\n' ' ')" >&2
    echo "         remove them (Settings > Environments > $ENV_NAME) if only $BRANCH should use the cloud identity." >&2
  fi
}

set_variables(){
  local ENTRY
  for ENTRY in "${VARIABLES[@]}"; do
    write gh variable set "${ENTRY%%=*}" --repo "$REPO" --env "$ENV_NAME" --body "${ENTRY#*=}"
    [ "$DRY_RUN" == "true" ] || echo "Set ${ENTRY%%=*} = ${ENTRY#*=}"
  done
  if [ -n "$CONFIG_ENV" ]; then
    write gh variable set SMOKE_TESTS_CONFIG_ENV --repo "$REPO" --body "$CONFIG_ENV"
    write gh variable set SMOKE_TESTS_CONFIG_TAG --repo "$REPO" --body "$CONFIG_TAG"
    [ "$DRY_RUN" == "true" ] || echo "Set the repository variables SMOKE_TESTS_CONFIG_ENV = $CONFIG_ENV and SMOKE_TESTS_CONFIG_TAG = $CONFIG_TAG"
  fi
}

print_subject(){
  echo ""
  echo "The trust rule of the $CLOUD identity must be limited to this subject (the GitHub token's sub claim):"
  echo "    repo:$REPO:environment:$ENV_NAME"
  echo "See $DOCS for the cloud side."
}

do_setup(){
  collect_variables
  put_environment
  put_branch_policy
  set_variables
  print_subject
}

do_show(){
  gh api "repos/$REPO/environments/$ENV_NAME" --jq '"Environment \(.name) of '"$REPO"': admins can bypass: \(.can_admins_bypass), branch rules: \(.deployment_branch_policy // "none" | tostring)"' \
    || die "No environment $ENV_NAME in $REPO"
  echo "Deployment branches:"
  gh api "repos/$REPO/environments/$ENV_NAME/deployment-branch-policies" --jq '.branch_policies[] | "  \(.name) (\(.type))"'
  echo "Reviewers:"
  gh api "repos/$REPO/environments/$ENV_NAME" --jq '[.protection_rules[]? | select(.type == "required_reviewers") | .reviewers[]? | "  \(.reviewer.login // .reviewer.slug)"] | if length == 0 then ["  none"] else . end | .[]'
  echo "Variables of the environment:"
  gh variable list --repo "$REPO" --env "$ENV_NAME"
  echo "Repository variables of the workflows:"
  gh variable list --repo "$REPO" | grep -E '^(NAME|SMOKE_TESTS_)' || echo "  none"
  print_subject
}

do_delete(){
  [ "$FORCE" == "true" ] || die "Deleting the environment $ENV_NAME and its variables needs --force"
  write gh api --method DELETE "repos/$REPO/environments/$ENV_NAME"
  echo "Deleted the environment $ENV_NAME of $REPO"
}

ACTION=
case "${1:-}" in
  setup|show|delete) ACTION="$1"; shift ;;
  -h|--help|"") usage; exit 0 ;;
  *) usage >&2; die "Unknown action '$1': it must be setup, show or delete" ;;
esac
CLOUD="${1:-}"
case "$CLOUD" in
  aws|azure|gcp) shift ;;
  *) usage >&2; die "Unknown cloud '$CLOUD': it must be aws, azure or gcp" ;;
esac

REPO=
BRANCH=main
REVIEWERS=()
CONFIG_ENV=
CONFIG_TAG=
DRY_RUN=false
FORCE=false
NAME="afon-smoke-tests"
POOL=github; PROVIDER=github

while [[ $# -gt 0 ]]; do
  # Every option but the flags takes a value.
  case "$1" in
    --dry-run) DRY_RUN=true; shift; continue ;;
    --force) FORCE=true; shift; continue ;;
    -h|--help) usage; exit 0 ;;
  esac
  [ $# -ge 2 ] || die "Option $1 requires a value"
  case "$1" in
    --repo) REPO="$2" ;;
    --branch) BRANCH="$2" ;;
    --reviewer) REVIEWERS+=("$2") ;;
    --config-env) CONFIG_ENV="$2" ;;
    --config-tag) CONFIG_TAG="$2" ;;
    --name) NAME="$2" ;;
    --pool) POOL="$2" ;;
    --provider) PROVIDER="$2" ;;
    *) usage >&2; die "Unknown option $1" ;;
  esac
  shift 2
done

[ -n "$REPO" ] || REPO="${GH_REPO:-$DEFAULT_REPO}"
export GH_REPO="$REPO"
ENV_NAME="$ENV_PREFIX$CLOUD"
if [ -n "$CONFIG_ENV$CONFIG_TAG" ] && { [ -z "$CONFIG_ENV" ] || [ -z "$CONFIG_TAG" ]; }; then
  die "Give both --config-env and --config-tag, or neither"
fi
if [ ${#REVIEWERS[@]} -gt 6 ]; then
  die "GitHub allows at most 6 reviewers"
fi

check_tools
echo "Repo $REPO, environment $ENV_NAME"
case "$ACTION" in
  setup) do_setup ;;
  show) do_show ;;
  delete) do_delete ;;
esac
