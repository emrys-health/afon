#!/usr/bin/env bash
# Debug mode helpers, sourced by afon.sh, afon-cli.sh and afon-cli-scripts.sh (the --debug flag / AFON_DEBUG=true).
# debug_enable <name> traces every command to stderr (file:line:function) and, on failure, reports the failing
# command, its exit code and the call stack.  debug_disable switches it off again.

DEBUG_SCRIPT=

# Prints a debug message to stderr, in the colour of afon.sh's error() when that is defined.
debug_msg(){
  if declare -F error >/dev/null; then
    error "$@"
  else
    echo "$*" >&2
  fi
}

# The innermost failure seen by the ERR trap: its exit code, command, location and call stack.
# Recorded, not reported: many failures are handled by the script (eg a grep that finds nothing in a
# command substitution or a pipeline), so only the one that ends the script is reported, by debug_on_exit.
DEBUG_FAIL_CODE=
DEBUG_FAIL_CMD=
DEBUG_FAIL_AT=
DEBUG_FAIL_STACK=
DEBUG_FAIL_DEPTH=0

# Prints the call stack of the script code, innermost first, skipping the frames of the debug helpers themselves.
debug_stack(){
  local i
  for ((i = 1; i < ${#FUNCNAME[@]}; i++)); do
    case "${FUNCNAME[$i]}" in
      debug_on_err|debug_on_exit|debug_stack) continue ;;
    esac
    echo "    at ${FUNCNAME[$i]}() ${BASH_SOURCE[$i]}:${BASH_LINENO[$((i - 1))]}"
  done
}

# ERR trap: records the failed command.  Not fatal by itself: set -e still decides whether the script stops.
# Args: the exit code, the command and the line.
debug_on_err(){
  local code="$1" cmd="$2" line="$3"
  { set +x; } 2>/dev/null # keep the helper itself out of the trace
  local depth=$((${#FUNCNAME[@]} - 1))
  # The ERR trap fires again, in each caller, as a failure propagates up: keep the innermost, unless it
  # is a later unrelated failure.
  if [ -z "$DEBUG_FAIL_CODE" ] || [ "$code" != "$DEBUG_FAIL_CODE" ] || [ "$depth" -ge "$DEBUG_FAIL_DEPTH" ]; then
    DEBUG_FAIL_CODE="$code"
    DEBUG_FAIL_CMD="$cmd"
    DEBUG_FAIL_AT="${BASH_SOURCE[1]:-?}:$line"
    DEBUG_FAIL_STACK="$(debug_stack)"
    DEBUG_FAIL_DEPTH="$depth"
  fi
  set -x
}

# EXIT trap: on a non-zero exit, reports why the script stopped.
debug_on_exit(){
  local code=$?
  { set +x; } 2>/dev/null
  trap - EXIT
  if [ "$code" -ne 0 ]; then
    debug_msg "[debug] $DEBUG_SCRIPT failed with exit code $code"
    if [ "$DEBUG_FAIL_CODE" == "$code" ]; then
      debug_msg "[debug]   failing command: $DEBUG_FAIL_CMD"
      debug_msg "[debug]   in $DEBUG_FAIL_AT"
      [ -z "$DEBUG_FAIL_STACK" ] || debug_msg "$DEBUG_FAIL_STACK"
    else
      debug_msg "[debug]   the script called exit; see the trace above for the last command run"
      if [ -n "$DEBUG_FAIL_CODE" ]; then
        debug_msg "[debug]   last failed command, which the script may have handled: $DEBUG_FAIL_CMD (in $DEBUG_FAIL_AT, exit code $DEBUG_FAIL_CODE)"
      fi
    fi
  fi
  return "$code"
}

# Switches on the debugging of the script $1: traces each command, and reports failures.
debug_enable(){
  DEBUG_SCRIPT="$1"
  debug_msg "[debug] Debug mode on for $DEBUG_SCRIPT: tracing commands to stderr, reporting why it fails"
  set -E # so that the ERR trap is inherited by functions and subshells
  trap 'debug_on_err $? "$BASH_COMMAND" $LINENO' ERR
  trap debug_on_exit EXIT
  PS4='+ ${BASH_SOURCE[0]##*/}:${LINENO}:${FUNCNAME[0]:-main}: '
  set -x
}

debug_disable(){
  set +x
  set +E
  trap - ERR EXIT
}
