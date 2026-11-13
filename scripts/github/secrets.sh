#!/usr/bin/env bash
# Publishes the afon config files to GitHub as secrets, for the workflows.
#
# github-secrets {push|list|delete} [--home <afon-home>] [--env <env>] [--repo <owner/repo>] [--dry-run]
#
# Without an env, the files in <afon-home>/config are published to the repository.
#
# With an env, the files in <afon-home>/config-<env> are published to the GitHub environment <env>, which is
# created if it does not exist yet.  The names are the same as the repository ones, and the environment ones
# take precedence over them in a workflow job that runs in that environment (see the 'config_env' input of the
# workflows), so the same workflow can be run against any config env.  The env is also taken from the global
# --config-env, --env or AFON_ENV, if --env is not given here.
#
# 'push' publishes each file as one base64-encoded secret, named after the file:
#      afon.properties         -> AFON_PROPERTIES
#      afon-aws.properties     -> AFON_AWS_PROPERTIES
#      catalogs.properties     -> CATALOGS_PROPERTIES
#      catalogs-aws.properties -> CATALOGS_AWS_PROPERTIES
#      secret-azure.properties -> SECRET_AZURE_PROPERTIES
#      users.csv               -> USERS_CSV
#      whitelist.yaml          -> WHITELIST_YAML
#    base64 is used so that multi-line values (SNOWFLAKE_PRIVATE_KEY, GOOGLE_APPLICATION_CREDENTIALS)
#    survive the round trip byte for byte.  Empty files are skipped, as GitHub secrets cannot be empty.
#
# GitHub secrets are write-only, so they cannot be read back.  To keep a copy of the config files and retrieve them
# again, use the config-vault command, which stores them in the secret store of a cloud.
#
# Requires a recent gh CLI, logged in (gh auth login, or GH_TOKEN) with the 'repo' scope, or for a fine-grained token
# read and write on Secrets, Variables and Administration.  Creating the environment requires admin access to the
# repo, and using environments in a private repo requires a paid plan.  See docs/acceptance_tests.md, section gh
# prerequisites.
#
# The repo is --repo, else env variable GH_REPO, else emrys-health/afon.  It does not depend on the current dir.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {push|list|delete} [--home <afon-home>] [--env <env>] [--repo <owner/repo>] [--dry-run]"
  echo "actions:  push   :  writes each config file to its secret"
  echo "          list   :  lists the secrets and variables currently set on the repo (or on the env)"
  echo "          delete :  removes the config secrets from the repo (or from the env), and the variable $LEGACY_VARIABLE"
  echo "                    that an earlier push set, if there is one"
  echo "home:       the afon home dir.  Defaults to \$HOME/.afon."
  echo "env:        the config env, which is also the name of the GitHub environment to hold the secrets."
  echo "            Reads or writes <afon-home>/config-<env>.  Defaults to the global config env, if set, else to"
  echo "            the repository and <afon-home>/config."
  echo "repo:       the target repo.  Defaults to env variable GH_REPO, if set, else to $DEFAULT_REPO."
  echo "dry-run:    the flag to report what would be done, without calling gh."
}

# Maps a config file name to its secret name: uppercased, with '-' and '.' as underscores.
secret_name(){
  echo "$1" | tr '.-' '__' | tr '[:lower:]' '[:upper:]'
}

gh_repo(){
  if [ -n "$REPO" ]; then
    echo "--repo $REPO"
  fi
}

gh_env(){
  if [ -n "$GH_ENV" ]; then
    echo "--env $GH_ENV"
  fi
}

# Describes where the secrets go, for the messages.
target(){
  if [ -n "$GH_ENV" ]; then
    echo "environment $GH_ENV"
  else
    echo "the repository"
  fi
}

# Creates the GitHub environment if not found.  Only create when missing, as the PUT resets
# the protection rules of an environment that already exists.
ensure_environment(){
  if [ -z "$GH_ENV" ] || [ "$DRY_RUN" == "true" ]; then
    return
  fi
  if gh api "repos/{owner}/{repo}/environments/$GH_ENV" --silent 2>/dev/null; then
    return
  fi
  echo "Creating GitHub environment $GH_ENV"
  gh api --method PUT "repos/{owner}/{repo}/environments/$GH_ENV" --silent
}

# The repo whose workflows use the secrets, as in scripts/github/runner.sh.
DEFAULT_REPO="emrys-health/afon"
# The variable that the pushes of an earlier version set, which held an encrypted copy of the config for a pull.  Nothing reads it now:
# delete removes it.
LEGACY_VARIABLE="AFON_CONFIG_BUNDLE"

push(){
  if [ ! -d "$CONFIG_DIR" ]; then
    echo "Missing config dir $CONFIG_DIR" >&2
    exit 1
  fi

  echo "Pushing config from $CONFIG_DIR to $(target)"
  ensure_environment
  for FILE in $CONFIG_FILES ; do {
    CONFIG_FILE="$CONFIG_DIR/$FILE"
    NAME=$(secret_name "$FILE")
    if [ ! -f "$CONFIG_FILE" ]; then
      echo "$NAME: skipped, no file $CONFIG_FILE"
      continue
    fi
    if [ ! -s "$CONFIG_FILE" ]; then
      echo "$NAME: skipped, $CONFIG_FILE is empty and GitHub secrets cannot be empty"
      continue
    fi
    if [ "$DRY_RUN" == "true" ]; then
      echo "$NAME: would set on $(target) from $CONFIG_FILE ($(wc -c < "$CONFIG_FILE" | tr -d ' ') bytes)"
      continue
    fi
    base64 < "$CONFIG_FILE" | tr -d '\n' | gh secret set "$NAME" $(gh_repo) $(gh_env)
    echo "$NAME: set on $(target) from $CONFIG_FILE"
  } done
}

list(){
  echo "Secrets of $(target)"
  gh secret list $(gh_repo) $(gh_env)
  echo "Variables of $(target)"
  gh variable list $(gh_repo) $(gh_env) --json name,updatedAt --jq '.[] | "\(.name)\tupdated \(.updatedAt)"'
}

delete(){
  for FILE in $CONFIG_FILES ; do {
    NAME=$(secret_name "$FILE")
    if [ "$DRY_RUN" == "true" ]; then
      echo "$NAME: would delete from $(target)"
      continue
    fi
    gh secret delete "$NAME" $(gh_repo) $(gh_env) && echo "$NAME: deleted from $(target)" || echo "$NAME: not deleted from $(target)"
  } done
  if [ "$DRY_RUN" == "true" ]; then
    echo "$LEGACY_VARIABLE: would delete from $(target), if set"
    return
  fi
  if gh variable get "$LEGACY_VARIABLE" $(gh_repo) $(gh_env) > /dev/null 2>&1; then
    gh variable delete "$LEGACY_VARIABLE" $(gh_repo) $(gh_env) && echo "$LEGACY_VARIABLE: deleted from $(target)"
  fi
}

ACTION="${1:-}"
shift || true

AFON_HOME=
GH_ENV=
REPO=
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)
      AFON_HOME="$2"; shift 2 ;;
    --env)
      GH_ENV="$2"; shift 2 ;;
    --repo)
      REPO="$2"; shift 2 ;;
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

# CONFIG_ENV is set when it has switched to a config env.
if [ -z "$GH_ENV" ] ; then
  GH_ENV="${CONFIG_ENV:-}"
fi
if [ -n "$GH_ENV" ] ; then
  CONFIG_DIR="$AFON_HOME/config-$GH_ENV"
  echo "Using env $GH_ENV"
else
  CONFIG_DIR="$AFON_HOME/config"
fi
echo "Using config dir $CONFIG_DIR"

# The repo whose workflows use the secrets is always given explicitly to gh, rather than left to gh to work out
# from the git remote of the current dir.  That fails outside a git repo, as when run via afon-cli-scripts.sh,
# which runs from the cli dir, and would push to the wrong repo from a checkout of another one.
# GH_REPO, which gh also honours, is exported for the gh api calls, that resolve {owner}/{repo} from it.
if [ -z "$REPO" ] ; then
  REPO="${GH_REPO:-$DEFAULT_REPO}"
fi
export GH_REPO="$REPO"
echo "Using repo $REPO"

# All clouds are always published, so that the workflows can run the specs for any of them.
CLOUD=
source $AFON_ROOT/scripts/config/config-files.sh

case "$ACTION" in
  push|list|delete)
    $ACTION
    ;;
  *)
    usage; exit 1 ;;
esac
