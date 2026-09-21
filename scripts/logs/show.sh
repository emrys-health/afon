#!/usr/bin/env bash
# Prints the Afon core log, ${AFON_HOME}/logs/afon-core.log, to stdout so it can be piped
# to whatever is needed.  The log is coloured with ANSI codes, which can be stripped to read,
# grep or copy it as plain text.
#
# Usage:
#   ./show.sh [<ansi>]
#   ansi: true|false, whether to keep the ANSI codes.  Defaults to true.
# Examples:
#   ./show.sh false | grep ERROR
#   ./show.sh false | tail -n 100

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [<ansi>]"
  echo "Prints ${AFON_HOME:-$HOME/.afon}/logs/afon-core.log."
  echo "ansi: true|false, whether to keep the ANSI codes.  Defaults to true."
}

# ${1:-} as afon.sh, which sources this script, runs with set -u
ANSI="${1:-true}"

case "$ANSI" in
  -h|--help)
    usage
    exit 0 ;;
  true|false) ;;
  *)
    echo "Invalid ansi value: $ANSI" >&2
    usage >&2
    exit 1 ;;
esac

LOG_FILE="${AFON_HOME:-$HOME/.afon}/logs/afon-core.log"

if [ ! -f "$LOG_FILE" ]; then
  echo "Log file not found: $LOG_FILE" >&2
  exit 1
fi

if [ "$ANSI" == "true" ]; then
  cat "$LOG_FILE"
else
  # LC_ALL=C as the log may contain bytes that are not valid in the current locale,
  # which would otherwise make sed fail on those lines.
  LC_ALL=C sed -E 's/\x1b\[[0-9;]*[A-Za-z]//g' "$LOG_FILE"
fi
