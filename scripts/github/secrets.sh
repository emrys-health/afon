#!/usr/bin/env bash
# Publishes the afon config files to GitHub, and retrieves them again.
#
# ./afon.sh -a github-secrets -sa "{push|pull|list|delete} [--home <afon-home>] [--env <env>] [--repo <owner/repo>] [--force] [--no-bundle] [--dry-run]"
#
# Without an env, the files in <afon-home>/config are published to the repository.
#
# With an env, the files in <afon-home>/config-<env> are published to the GitHub environment <env>, which is
# created if it does not exist yet.  The names are the same as the repository ones, and the environment ones
# take precedence over them in a workflow job that runs in that environment (see the 'config_env' input of the
# workflows), so the same workflow can be run against any config env.  The env is also taken from the global
# --config-env, --env or AFON_ENV of afon.sh, if --env is not given here.
#
# 'push' publishes two things:
#
# 1. The secrets, for the workflows.  Each file becomes one base64-encoded secret, named after the file:
#      afon.properties         -> AFON_PROPERTIES
#      afon-aws.properties     -> AFON_AWS_PROPERTIES
#      catalogs.properties     -> CATALOGS_PROPERTIES
#      catalogs-aws.properties -> CATALOGS_AWS_PROPERTIES
#      secret-azure.properties -> SECRET_AZURE_PROPERTIES
#      users.csv               -> USERS_CSV
#    base64 is used so that multi-line values (SNOWFLAKE_PRIVATE_KEY, GOOGLE_APPLICATION_CREDENTIALS)
#    survive the round trip byte for byte.  Empty files are skipped, as GitHub secrets cannot be empty.
#
# 2. The bundle, for 'pull'.  GitHub secrets are write-only, so they cannot be read back.  All the config files,
#    empty ones included, are also put in a tar, encrypted with AES-256 (PBKDF2, 600000 iterations) under a
#    passphrase, and stored as the GitHub variable AFON_CONFIG_BUNDLE.  Unlike the secrets, variables can be read
#    by anyone with read access to the repo, so the bundle is only as safe as its passphrase: it must be at least
#    16 characters, and shared with the team out of band.  Give it as env variable AFON_SECRETS_PASSPHRASE, or
#    it is prompted for.  Use --no-bundle to publish the secrets only.
#
# 'pull' reads the bundle, decrypts it and writes the files back to the config dir.  It needs neither the
# workflows nor the secrets.
#
# Requires the gh CLI, authenticated with a token holding the 'repo' scope, and openssl with PBKDF2 support
# (OpenSSL 1.1.1+ or LibreSSL 3.1+).  Creating an environment requires admin access to the repo.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {push|pull|list|delete} [--home <afon-home>] [--env <env>] [--repo <owner/repo>] [--force] [--no-bundle] [--dry-run]"
  echo "actions:  push   :  writes each config file to its secret, and the encrypted bundle of them all to variable $BUNDLE_VARIABLE"
  echo "          pull   :  writes the config files held in the bundle back into the config dir.  Refuses to"
  echo "                    touch a non-empty config dir without --force"
  echo "          list   :  lists the secrets and variables currently set on the repo (or on the env)"
  echo "          delete :  removes the config secrets and the bundle from the repo (or from the env)"
  echo "home:       the afon home dir.  Defaults to \$HOME/.afon."
  echo "env:        the config env, which is also the name of the GitHub environment to hold the secrets."
  echo "            Reads or writes <afon-home>/config-<env>.  Defaults to the global config env, if set, else to"
  echo "            the repository and <afon-home>/config."
  echo "repo:       the target repo.  Defaults to the repo of the current dir."
  echo "force:      the flag to let pull overwrite the files of a non-empty config dir."
  echo "no-bundle:  the flag to let push publish the secrets only, without the bundle and its passphrase."
  echo "dry-run:    the flag to report what would be done, without calling gh."
  echo "The bundle passphrase is read from env variable AFON_SECRETS_PASSPHRASE, else prompted for."
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

BUNDLE_VARIABLE="AFON_CONFIG_BUNDLE"
# GitHub variables are limited to 48 KB.
BUNDLE_MAX_BYTES=48000
PASSPHRASE_MIN_LENGTH=16
PBKDF2_ITERATIONS=600000

# Removes the work dir, which holds the decrypted config, and stops.
pull_fail(){
  echo "$1" >&2
  rm -rf "${WORK_DIR:-}"
  exit 1
}

check_openssl(){
  if ! echo x | openssl enc -aes-256-cbc -md sha256 -pbkdf2 -pass pass:x > /dev/null 2>&1 ; then
    echo "Requires openssl with PBKDF2 support (OpenSSL 1.1.1+ or LibreSSL 3.1+): $(command -v openssl || echo 'openssl not found')" >&2
    exit 1
  fi
}

# Sets PASSPHRASE from env variable AFON_SECRETS_PASSPHRASE, else prompts for it.  With $1=new the passphrase
# is checked for strength and confirmed, as it is going to protect the bundle.
get_passphrase(){
  PASSPHRASE="${AFON_SECRETS_PASSPHRASE:-}"
  if [ -z "$PASSPHRASE" ]; then
    if [ ! -t 0 ]; then
      echo "No passphrase: set env variable AFON_SECRETS_PASSPHRASE, as there is no terminal to prompt on" >&2
      exit 1
    fi
    read -rsp "Passphrase for the config bundle: " PASSPHRASE
    echo
    if [ "${1:-}" == "new" ]; then
      local CONFIRM
      read -rsp "Confirm passphrase: " CONFIRM
      echo
      if [ "$PASSPHRASE" != "$CONFIRM" ]; then
        echo "The passphrases differ" >&2
        exit 1
      fi
    fi
  fi
  if [ "${1:-}" == "new" ] && [ ${#PASSPHRASE} -lt $PASSPHRASE_MIN_LENGTH ]; then
    echo "The passphrase must be at least $PASSPHRASE_MIN_LENGTH characters, as the bundle can be read by anyone with read access to the repo" >&2
    exit 1
  fi
}

# Runs openssl enc with $@, taking the passphrase from PASSPHRASE without it showing in the process list.
crypt(){
  AFON_SECRETS_PASSPHRASE="$PASSPHRASE" openssl enc -aes-256-cbc -md sha256 -pbkdf2 -iter $PBKDF2_ITERATIONS \
    -pass env:AFON_SECRETS_PASSPHRASE "$@"
}

# The config files present in the config dir.
bundle_files(){
  local FILE
  for FILE in $CONFIG_FILES ; do {
    if [ -f "$CONFIG_DIR/$FILE" ]; then
      echo "$FILE"
    fi
  } done
}

# Writes the bundle to stdout: the config files as a tar, encrypted, as a single line of base64.
# COPYFILE_DISABLE stops the macOS tar from adding ._ files for the extended attributes.  The tar is
# gzipped separately, as the macOS tar does not compress when writing to a pipe with -z.
bundle_encrypt(){
  COPYFILE_DISABLE=1 tar -C "$CONFIG_DIR" -cf - $(bundle_files) | gzip -c | crypt -salt | base64 | tr -d '\n'
}

# Reads the bundle from stdin, decrypts it and extracts the config files to dir $1.
bundle_decrypt(){
  base64 -d | crypt -d | gzip -dc | tar -xf - -C "$1"
}

push(){
  if [ ! -d "$CONFIG_DIR" ]; then
    echo "Missing config dir $CONFIG_DIR" >&2
    exit 1
  fi

  # Done first, so that a bad passphrase or a bundle too big stops us before anything is published.
  local BUNDLE=
  if [ "$BUNDLE_ENABLED" == "true" ] && [ "$DRY_RUN" != "true" ]; then
    check_openssl
    get_passphrase new
    BUNDLE=$(bundle_encrypt) || { echo "Failed to encrypt the bundle" >&2; exit 1; }
    if [ ${#BUNDLE} -gt $BUNDLE_MAX_BYTES ]; then
      echo "The bundle is ${#BUNDLE} bytes, over the $BUNDLE_MAX_BYTES bytes GitHub allows for a variable" >&2
      exit 1
    fi
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

  if [ "$BUNDLE_ENABLED" != "true" ]; then
    echo "$BUNDLE_VARIABLE: skipped, --no-bundle"
  elif [ "$DRY_RUN" == "true" ]; then
    echo "$BUNDLE_VARIABLE: would set on $(target) from $(bundle_files | wc -l | tr -d ' ') files in $CONFIG_DIR, encrypted"
  else
    printf '%s' "$BUNDLE" | gh variable set "$BUNDLE_VARIABLE" $(gh_repo) $(gh_env)
    echo "$BUNDLE_VARIABLE: set on $(target) from $(bundle_files | wc -l | tr -d ' ') files in $CONFIG_DIR, encrypted (${#BUNDLE} bytes)"
  fi
}

pull(){
  if [ -d "$CONFIG_DIR" ] && [ -n "$(ls -A "$CONFIG_DIR")" ] && [ "$FORCE" != "true" ]; then
    echo "Config dir $CONFIG_DIR is not empty.  Use --force to overwrite its files." >&2
    exit 1
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "$BUNDLE_VARIABLE: would read from $(target) and extract the config files to $CONFIG_DIR"
    return
  fi
  check_openssl
  get_passphrase

  local BUNDLE
  BUNDLE=$(gh variable get "$BUNDLE_VARIABLE" $(gh_repo) $(gh_env)) \
    || { echo "No $BUNDLE_VARIABLE on $(target).  Was it pushed, and without --no-bundle?" >&2; exit 1; }

  WORK_DIR=$(mktemp -d)
  chmod 700 "$WORK_DIR"
  printf '%s' "$BUNDLE" | bundle_decrypt "$WORK_DIR" 2> /dev/null || pull_fail "Failed to decrypt the bundle: wrong passphrase?"

  # Only plain files with a plain name are taken, whatever the bundle holds.
  mkdir -p "$CONFIG_DIR"
  local CONFIG_FILE NAME COUNT=0
  for CONFIG_FILE in "$WORK_DIR"/* ; do {
    NAME=$(basename "$CONFIG_FILE")
    if [ ! -f "$CONFIG_FILE" ] || [ -L "$CONFIG_FILE" ] || [[ ! "$NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
      echo "$NAME: skipped, not a plain file"
      continue
    fi
    cp "$CONFIG_FILE" "$CONFIG_DIR/$NAME"
    chmod 600 "$CONFIG_DIR/$NAME"
    echo "$NAME: written to $CONFIG_DIR"
    COUNT=$((COUNT + 1))
  } done
  rm -rf "$WORK_DIR"
  echo "Pulled $COUNT config files from $(target) to $CONFIG_DIR"
}

list(){
  echo "Secrets of $(target)"
  gh secret list $(gh_repo) $(gh_env)
  # The value of the bundle is not shown, as it is long.
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
    echo "$BUNDLE_VARIABLE: would delete from $(target)"
    return
  fi
  gh variable delete "$BUNDLE_VARIABLE" $(gh_repo) $(gh_env) && echo "$BUNDLE_VARIABLE: deleted from $(target)" || echo "$BUNDLE_VARIABLE: not deleted from $(target)"
}

ACTION="${1:-}"
shift || true

AFON_HOME=
GH_ENV=
REPO=
FORCE=false
BUNDLE_ENABLED=true
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)
      AFON_HOME="$2"; shift 2 ;;
    --env)
      GH_ENV="$2"; shift 2 ;;
    --repo)
      REPO="$2"; shift 2 ;;
    --force)
      FORCE=true; shift ;;
    --no-bundle)
      BUNDLE_ENABLED=false; shift ;;
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

# gh api resolves {owner}/{repo} from the current dir, unless GH_REPO is set.
if [ -n "$REPO" ] ; then
  export GH_REPO="$REPO"
fi

# All clouds are always published, so that the workflows can run the specs for any of them.
CLOUD=
source $AFON_ROOT/scripts/config/config-files.sh
# The generic catalogs.properties is not among the files listed by config-files.sh, but is needed just the same
# as the catalogs-<cloud>.properties that are.
CONFIG_FILES="$CONFIG_FILES catalogs.properties"

case "$ACTION" in
  push|list|delete|pull)
    $ACTION
    ;;
  *)
    usage; exit 1 ;;
esac
