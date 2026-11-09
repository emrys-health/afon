#!/usr/bin/env bash
# Wraps the gh workflow commands for the per-cloud workflows of the repo, eg the smoke tests, which are the files
# .github/workflows/<name>-<cloud>.yml, eg smoke-tests-aws.yml.
#
# github-workflow {list|view|enable|disable|run} [--cloud {aws|azure|gcp|all}] [--name <name>] [--repo <owner/repo>]
#                 [--ref <branch>] [--field <key=value>]... [--all] [--dry-run]
#
#   list      lists the workflows of the repo, with their state (also the disabled ones), eg to see which are paused.  Only
#             those of the files in .github/workflows of the default branch, so not the ones GitHub runs itself, eg CodeQL, nor the
#             entries left by files that were deleted; --all lists every workflow
#   view      shows the workflow <name>-<cloud>, with its recent runs
#   enable    enables the workflow, eg to resume the smoke tests
#   disable   disables the workflow, so that no run starts, whether triggered by a build or by hand: runs in progress are not stopped
#   run       starts the workflow, which must have a workflow_dispatch trigger, on --ref (the default branch if not given), with the
#             inputs given as --field, eg --field config_env=emrys3
#
# The workflow of the action is --name (default smoke-tests) for --cloud: <name>-<cloud>.  With --cloud all the action applies to
# the workflow of every cloud, ie <name>-aws, <name>-azure and <name>-gcp, and carries on after one fails.  The
# actions but list need a --cloud, which afon-cli.sh passes on as env variable AFON_CLOUD, so that is read too.
#
# Requires the gh CLI, logged in (gh auth login, or GH_TOKEN) with the 'repo' scope; enabling, disabling and running need write access
# to the repo.
#
# The repo is --repo, else env variable GH_REPO, else emrys-health/afon.  It does not depend on the current dir.
set -euo pipefail

DEFAULT_REPO="emrys-health/afon"
DEFAULT_NAME="smoke-tests"
CLOUDS=(aws azure gcp)

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {list|view|enable|disable|run} [--cloud {aws|azure|gcp|all}] [--name <name>] [--repo <owner/repo>]"
  echo "          [--ref <branch>] [--field <key=value>]... [--all] [--dry-run]"
  echo "actions:  list    :  lists the workflows of the repo and their state"
  echo "          view    :  shows the workflow and its recent runs"
  echo "          enable  :  enables the workflow"
  echo "          disable :  disables the workflow, so that no run starts.  Runs in progress are not stopped"
  echo "          run     :  starts the workflow, on --ref, with the inputs given as --field"
  echo "cloud:    the cloud of the workflow, or all for the workflow of every cloud.  Needed by all actions but list."
  echo "          afon-cli.sh takes --cloud (or -c) itself, so this is also env variable AFON_CLOUD."
  echo "name:     the workflow, as <name>-<cloud>.  Defaults to $DEFAULT_NAME."
  echo "repo:     the target repo.  Defaults to env variable GH_REPO, if set, else to $DEFAULT_REPO."
  echo "ref:      for run, the branch or tag to run the workflow on.  Defaults to the default branch of the repo."
  echo "field:    for run, an input of the workflow as key=value, repeatable, eg config_env=emrys3."
  echo "all:      the flag to let list show every workflow, also those GitHub runs itself, and the entries of deleted files."
  echo "dry-run:  the flag to say what would be run, without running it."
}

die(){
  echo "$1" >&2
  exit 1
}

check_tools(){
  command -v gh > /dev/null 2>&1 || die "Requires gh on the PATH"
  gh auth status > /dev/null 2>&1 || die "gh is not logged in: run gh auth login, or set GH_TOKEN"
}

# Runs the command, or says it would, with --dry-run.
write(){
  if [ "$DRY_RUN" == "true" ]; then
    echo "would run: $*"
  else
    "$@"
  fi
}

# Runs the command, whose output is tab separated, as gh prints it when it is not a terminal, as a table with aligned columns.
# Takes the header of the columns, if any, as the first arg, where an empty one says there is none.
table(){
  local HEADER="$1"
  shift
  if [ "$DRY_RUN" == "true" ]; then
    echo "would run: $*"
  elif command -v column > /dev/null 2>&1; then
    { [ -z "$HEADER" ] || printf '%b\n' "$HEADER"; "$@"; } | column -t -s $'\t'
  else
    "$@"
  fi
}

# Lists the workflows of the files in .github/workflows of the default branch, or every workflow with --all, as a table.
list_workflows(){
  if [ "$ALL" == "true" ]; then
    table "NAME\tSTATE\tID" gh workflow list --all
    return
  fi
  if [ "$DRY_RUN" == "true" ]; then
    echo "would run: gh api repos/$REPO/actions/workflows, limited to the files in .github/workflows"
    return
  fi
  local FILES
  FILES="$(gh api "repos/$REPO/contents/.github/workflows" --jq '.[].path')"
  {
    printf 'NAME\tSTATE\tID\tFILE\n'
    gh api "repos/$REPO/actions/workflows" --paginate --jq '.workflows[] | [.name, .state, .id, .path] | @tsv' |
      while IFS=$'\t' read -r WF_NAME WF_STATE WF_ID WF_PATH; do
        if grep -qxF "$WF_PATH" <<< "$FILES"; then
          printf '%s\t%s\t%s\t%s\n' "$WF_NAME" "$WF_STATE" "$WF_ID" "$WF_PATH"
        fi
      done
  } | { command -v column > /dev/null 2>&1 && column -t -s $'\t' || cat; }
}

# Applies the action to the workflow file of the cloud, and prints its name first.
apply(){
  local CLOUD_NAME="$1" WORKFLOW="${NAME}-${1}.yml" FIELD
  echo "== $WORKFLOW"
  case "$ACTION" in
    view) write gh workflow view "$WORKFLOW" ;;
    enable) write gh workflow enable "$WORKFLOW" ;;
    disable) write gh workflow disable "$WORKFLOW" ;;
    run)
      local ARGS=()
      [ -z "$REF" ] || ARGS+=(--ref "$REF")
      for FIELD in ${FIELDS[@]+"${FIELDS[@]}"}; do
        ARGS+=(--field "$FIELD")
      done
      write gh workflow run "$WORKFLOW" ${ARGS[@]+"${ARGS[@]}"} ;;
  esac
}

[ $# -ge 1 ] || { usage >&2; exit 1; }
case "$1" in
  -h|--help) usage; exit 0 ;;
  list|view|enable|disable|run) ACTION="$1"; shift ;;
  *) usage >&2; die "Unknown action $1" ;;
esac

REPO=
NAME="$DEFAULT_NAME"
CLOUD="${AFON_CLOUD:-}" # afon-cli.sh takes --cloud itself, and passes it on as AFON_CLOUD
REF=
FIELDS=()
DRY_RUN=false
ALL=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift; continue ;;
    --all) ALL=true; shift; continue ;;
    -h|--help) usage; exit 0 ;;
  esac
  [ $# -ge 2 ] || die "Option $1 requires a value"
  case "$1" in
    --repo) REPO="$2" ;;
    --name) NAME="$2" ;;
    --cloud) CLOUD="$2" ;;
    --ref) REF="$2" ;;
    --field) FIELDS+=("$2") ;;
    *) usage >&2; die "Unknown option $1" ;;
  esac
  shift 2
done

[ -n "$REPO" ] || REPO="${GH_REPO:-$DEFAULT_REPO}"
export GH_REPO="$REPO"
if [ "$ACTION" != "run" ] && { [ -n "$REF" ] || [ ${#FIELDS[@]} -gt 0 ]; }; then
  die "--ref and --field are for run only"
fi
if [ "$ALL" == "true" ] && [ "$ACTION" != "list" ]; then
  die "--all is for list only"
fi

check_tools
echo "Repo $REPO"
if [ "$ACTION" == "list" ]; then
  list_workflows
  exit 0
fi

[ -n "$CLOUD" ] || die "Give the --cloud, one of aws, azure, gcp or all"
CLOUD="$(printf '%s' "$CLOUD" | tr '[:upper:]' '[:lower:]')"
if [ "$CLOUD" == "all" ]; then
  TARGETS=("${CLOUDS[@]}")
else
  case "$CLOUD" in
    aws|azure|gcp) TARGETS=("$CLOUD") ;;
    *) die "Unknown cloud $CLOUD: use aws, azure, gcp or all" ;;
  esac
fi

FAILED=0
for TARGET in "${TARGETS[@]}"; do
  apply "$TARGET" || { echo "Failed: $ACTION of ${NAME}-${TARGET}" >&2; FAILED=$((FAILED + 1)); }
done
[ "$FAILED" -eq 0 ] || die "$FAILED of ${#TARGETS[@]} failed"
