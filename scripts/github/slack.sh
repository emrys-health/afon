#!/usr/bin/env bash
# Notifies the Slack channel of the CI workflows, #afon-ci, of a failed job, and of a job that passes again after a
# failure.  Run by the workflows as their last step, see docs/github_workflows.md, section Slack notifications.
#
# scripts/github/slack.sh {failure|recovery}
#
# 'failure' posts that the job failed.
#
# 'recovery' posts that the job passed again, if the run before it failed, and else posts nothing.  The run before is
# the previous attempt of the same run, when it is re-run, else the latest earlier run of the same workflow on the
# same branch.  Skipped and cancelled runs are passed over, as they say nothing of whether the job works.
#
# The channel is the one the incoming webhook was created for, given as env variable SLACK_WEBHOOK_URL.  Without it,
# or if Slack or the GitHub API cannot be reached, it only warns, so that a notification never fails the job.
#
# Requires curl and jq, the GITHUB_* env variables that GitHub Actions sets, and for 'recovery' env variable
# GITHUB_TOKEN, with the 'actions: read' permission.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {failure|recovery}"
  echo "actions:  failure   :  posts that the job failed"
  echo "          recovery  :  posts that the job passed again, if the run before it failed"
}

warn(){
  echo "::warning::$1"
}

# Prints the JSON response of the GitHub API for the path, or fails.
github_api(){
  curl -sSf -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "$GITHUB_API_URL/repos/$GITHUB_REPOSITORY/$1"
}

# Prints the conclusion of the run before this one: failure, success, timed_out, etc.
previous_conclusion(){
  if [ "${GITHUB_RUN_ATTEMPT:-1}" -gt 1 ]; then
    github_api "actions/runs/$GITHUB_RUN_ID/attempts/$((GITHUB_RUN_ATTEMPT - 1))" | jq -r '.conclusion // ""'
  else
    # GITHUB_WORKFLOW_REF is <owner>/<repo>/.github/workflows/<file>@<ref>.  This run is still in progress, so it is
    # not among the completed ones.
    local WORKFLOW_FILE
    WORKFLOW_FILE=$(basename "${GITHUB_WORKFLOW_REF%@*}")
    github_api "actions/workflows/$WORKFLOW_FILE/runs?branch=$GITHUB_REF_NAME&status=completed&per_page=30" \
      | jq -r --argjson id "$GITHUB_RUN_ID" '
          [.workflow_runs[] | select(.id != $id and (.conclusion | IN("skipped", "cancelled") | not))]
          | first | .conclusion // ""'
  fi
}

post(){
  jq -n --arg text "$1" '{text: $text}' \
    | curl -sSf -X POST -H 'Content-type: application/json' --data @- "$SLACK_WEBHOOK_URL" > /dev/null
}

ACTION="${1:-}"
case "$ACTION" in
  failure|recovery) ;;
  *) usage; exit 1 ;;
esac

if [ -z "${SLACK_WEBHOOK_URL:-}" ]; then
  warn "No SLACK_WEBHOOK_URL secret, so no Slack notification sent"
  exit 0
fi

RUN_URL="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
RUN_DETAILS="on \`$GITHUB_REF_NAME\` (\`${GITHUB_SHA::8}\`, $GITHUB_EVENT_NAME by $GITHUB_ACTOR): <$RUN_URL|run $GITHUB_RUN_ID>"

if [ "$ACTION" == "failure" ]; then
  TEXT=":x: *$GITHUB_WORKFLOW* failed $RUN_DETAILS"
else
  if ! PREVIOUS=$(previous_conclusion); then
    warn "Could not get the run before this one from the GitHub API, so no Slack notification sent"
    exit 0
  fi
  case "$PREVIOUS" in
    failure|timed_out|startup_failure) ;;
    *)
      echo "The run before this one did not fail (${PREVIOUS:-none found}), so no Slack notification sent"
      exit 0 ;;
  esac
  TEXT=":white_check_mark: *$GITHUB_WORKFLOW* passed again $RUN_DETAILS"
fi

if ! post "$TEXT"; then
  warn "Could not post to Slack, so no Slack notification sent"
  exit 0
fi
echo "Notified Slack of the $ACTION"
