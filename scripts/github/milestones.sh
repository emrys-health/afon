#!/usr/bin/env bash
# Generates the release notes of a GitHub milestone, named after the release version, listing its issues.
#
# ./afon.sh -a github-milestones -- <version> [<notes-file>] [--state <state>] [--repo <owner/repo>]
#
# The notes are written to <afon-home>/milestone-<version>.md, where <afon-home> defaults to $HOME/.afon, and, if a
# notes file is given (e.g. release_notes.md), its '## <version>' section is replaced, or inserted before the first
# '## ' section if not present.
#
# Requires the gh CLI, authenticated with a token holding the 'repo' scope.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <version> [<notes-file>] [--state <state>] [--repo <owner/repo>]"
  echo "version:     the release version, matching the milestone title, e.g. 1.0-beta-6"
  echo "notes-file:  the optional markdown file to update with the milestone section"
  echo "state:       the state of the issues to list: all|open|closed (or done, same as closed).  Defaults to all."
  echo "repo:        the source repo.  Defaults to the repo of the current dir."
}

VERSION=
NOTES_FILE=
STATE=all

while [[ $# -gt 0 ]]; do
  case "$1" in
    --state)
      STATE="$2"; shift 2 ;;
    --repo)
      export GH_REPO="$2"; shift 2 ;;
    --help|-h)
      usage; exit 0 ;;
    -*)
      echo "Unknown option: $1" >&2; usage; exit 1 ;;
    *)
      if [ -z "$VERSION" ]; then
        VERSION="$1"
      elif [ -z "$NOTES_FILE" ]; then
        NOTES_FILE="$1"
      else
        echo "Unexpected arg: $1" >&2; usage; exit 1
      fi
      shift ;;
  esac
done

if [ -z "$VERSION" ]; then
  usage; exit 1
fi
case "$STATE" in
  all|open|closed) ;;
  done) STATE=closed ;;
  *) echo "Unknown state: $STATE" >&2; usage; exit 1 ;;
esac
if [ -n "$NOTES_FILE" ] && [ ! -f "$NOTES_FILE" ]; then
  echo "Notes file $NOTES_FILE not found" >&2; exit 1
fi

# Looks up the milestone by title: number, url and due date (yyyy-mm-dd, empty if not set), tab-separated
MILESTONE=$(gh api --paginate "repos/{owner}/{repo}/milestones?state=all&per_page=100" \
  --jq ".[] | select(.title == \"$VERSION\") | [.number, .html_url, ((.due_on // \"\")[0:10])] | @tsv")
if [ -z "$MILESTONE" ]; then
  echo "No milestone found with title $VERSION" >&2; exit 1
fi
IFS=$'\t' read -r NUMBER URL DUE <<< "$MILESTONE"

# Lists the milestone issues in the given state, excluding pull requests, as markdown list items
issues(){
  gh api --paginate "repos/{owner}/{repo}/issues?milestone=$NUMBER&state=$1&per_page=100" \
    --jq '.[] | select(.pull_request == null)
      | (.labels | map(.name) | join(", ")) as $labels
      | "- " + (if $labels == "" then "" else "**\($labels)** — " end) + "[#\(.number)](\(.html_url)) \(.title)"'
}

CLOSED=
OPEN=
if [ "$STATE" != "open" ]; then
  CLOSED=$(issues closed)
fi
if [ "$STATE" != "closed" ]; then
  OPEN=$(issues open)
fi

count(){
  if [ -z "$1" ]; then echo 0; else echo "$1" | wc -l | tr -d ' '; fi
}

# HOME_DIR is the afon home resolved by afon.sh (incl --home), AFON_HOME the one resolved by afon-cli-scripts.sh
MILESTONE_FILE="${HOME_DIR:-${AFON_HOME:-$HOME/.afon}}/milestone-$VERSION.md"
mkdir -p "$(dirname "$MILESTONE_FILE")"
{
  echo "## $VERSION"
  echo
  echo "**Milestone:** [$VERSION]($URL)"
  if [ -n "$DUE" ]; then
    echo "**Due:** $DUE"
  fi
  # The requested state is always listed, even if empty, while with 'all' the open issues are listed only if any
  if [ "$STATE" != "open" ]; then
    echo
    echo "### Closed Issues ($(count "$CLOSED"))"
    if [ -n "$CLOSED" ]; then
      echo
      echo "$CLOSED"
    fi
  fi
  if [ "$STATE" == "open" ] || [ -n "$OPEN" ]; then
    echo
    echo "### Open Issues ($(count "$OPEN"))"
    if [ -n "$OPEN" ]; then
      echo
      echo "$OPEN"
    fi
  fi
} > "$MILESTONE_FILE"
echo "Milestone $VERSION written to $MILESTONE_FILE"

if [ -z "$NOTES_FILE" ]; then
  exit 0
fi

# Replaces the existing version section, or inserts it before the first section (appends if there is none)
if grep -qxF "## $VERSION" "$NOTES_FILE"; then
  MODE=replace
else
  MODE=insert
fi
TMP_FILE=$(mktemp)
awk -v heading="## $VERSION" -v section="$MILESTONE_FILE" -v mode="$MODE" '
  function emit(  line) { while ((getline line < section) > 0) print line; close(section); print ""; done = 1 }
  /^## / {
    skipping = 0
    if (mode == "replace" && $0 == heading) { emit(); skipping = 1; next }
    if (mode == "insert" && !done) emit()
  }
  !skipping { print }
  END { if (!done) { print ""; emit() } }
' "$NOTES_FILE" > "$TMP_FILE"
mv "$TMP_FILE" "$NOTES_FILE"
echo "Milestone $VERSION section updated in $NOTES_FILE ($MODE)"
