#!/usr/bin/env bash
# Publishes the afon config files as GitHub repository secrets, for use by the
# acceptance test workflow (.github/workflows/acceptance-tests-linux.yml).
#
# ./secrets.sh {push|list|delete} [--home <afon-home>] [--repo <owner/repo>] [--dry-run]
#
# Each file in <afon-home>/config becomes one base64-encoded secret, named after the file:
#   afon.properties         -> AFON_PROPERTIES
#   afon-aws.properties     -> AFON_AWS_PROPERTIES
#   secret-azure.properties -> SECRET_AZURE_PROPERTIES
#   users.csv               -> USERS_CSV
#
# base64 is used so that multi-line values (SNOWFLAKE_PRIVATE_KEY, GOOGLE_APPLICATION_CREDENTIALS)
# survive the round trip byte for byte. The workflow decodes them back into $AFON_HOME/config.
#
# Requires the gh CLI, authenticated with a token holding the 'repo' scope.
set -euo pipefail

usage(){
  echo "Usage: $0 {push|list|delete} [--home <afon-home>] [--repo <owner/repo>] [--dry-run]"
  echo "actions:  push   :  writes each config file to its secret, overwriting any existing value"
  echo "          list   :  lists the secrets currently set on the repo"
  echo "          delete :  removes the config secrets from the repo"
  echo "home:     the afon home dir.  Defaults to \$HOME/.afon."
  echo "repo:     the target repo.  Defaults to the repo of the current dir."
  echo "dry-run:  the flag to report what would be done, without calling gh."
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

push(){
  echo "Pushing config secrets from $CONFIG_DIR"
  for FILE in $CONFIG_FILES ; do {
    CONFIG_FILE="$CONFIG_DIR/$FILE"
    NAME=$(secret_name "$FILE")
    if [ ! -f "$CONFIG_FILE" ]; then
      echo "$NAME: skipped, no file $CONFIG_FILE"
      continue
    fi
    if [ "$DRY_RUN" == "true" ]; then
      echo "$NAME: would set from $CONFIG_FILE ($(wc -c < "$CONFIG_FILE" | tr -d ' ') bytes)"
      continue
    fi
    base64 < "$CONFIG_FILE" | tr -d '\n' | gh secret set "$NAME" $(gh_repo)
    echo "$NAME: set from $CONFIG_FILE"
  } done
}

list(){
  gh secret list $(gh_repo)
}

delete(){
  for FILE in $CONFIG_FILES ; do {
    NAME=$(secret_name "$FILE")
    if [ "$DRY_RUN" == "true" ]; then
      echo "$NAME: would delete"
      continue
    fi
    gh secret delete "$NAME" $(gh_repo) && echo "$NAME: deleted" || echo "$NAME: not deleted"
  } done
}

ACTION="${1:-}"
shift || true

AFON_HOME=
REPO=
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)
      AFON_HOME="$2"; shift 2 ;;
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
CONFIG_DIR="$AFON_HOME/config"
echo "Using config dir $CONFIG_DIR"

# All clouds are always published, so that the workflow can run the specs for any of them.
CLOUD=
source "$(dirname "$0")/../config/config-files.sh"

case "$ACTION" in
  push|list|delete)
    $ACTION
    ;;
  *)
    usage; exit 1 ;;
esac
