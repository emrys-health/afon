# Sourced by the scripts that act on a stack of stacks.yaml, to have what they need to know about it in their env, whichever way
# they are run:
#   afon-cli.sh <command>   the launcher, ScriptMain, finds the stack and gives it already, as the AFON_STACK_* env vars
#   afon.sh -a <command>    sources the script in bash, with no JVM, so the stack is asked of the ops command stack-needs
#   directly                as afon.sh does, if there is a launcher to ask: the one in AFON_ROOT, ie afon-cli.sh of the cli
#                           distribution or afon.sh of the source tree, else AFON_CLI_DIR/afon-cli.sh
#
# stack_needs <cloud> <stack-id|role|none> [<component>]
#   With a stack, given by its id or its role in the cloud, exports AFON_STACK_ID, AFON_STACK_ROLE, AFON_STACK_DOMAINS and
#   AFON_STACK_COMPONENTS.  With none and a component, eg Polaris, exports AFON_STACK_CANDIDATES, the ids of the stacks of the
#   cloud that deploy it.  Returns 1, saying why, if it cannot.
stack_needs() {
  local cloud="$1" stack="$2" component="${3:-}" output line
  # given already by the launcher
  if [ "$stack" != "none" ] && [ -n "${AFON_STACK_ID:-}" ]; then
    return 0
  fi
  if [ "$stack" = "none" ] && [ -n "$component" ] && [ -n "${AFON_STACK_CANDIDATES+x}" ]; then
    return 0
  fi
  # The launcher of the tree the script is from, as AFON_ROOT is the cli distribution when run by afon-cli, and the source tree
  # when run by afon.sh: not AFON_CLI_DIR first, which may be an installed distribution of another version, that does not know
  # the command, if the script is from the source tree.
  if [ -x "${AFON_ROOT:-}/afon-cli.sh" ]; then
    output=$("$AFON_ROOT/afon-cli.sh" stack-needs "$cloud" "$stack" $component 2>&1) || { echo "$output" >&2; return 1; }
  elif [ -x "${AFON_ROOT:-}/afon.sh" ]; then
    output=$("$AFON_ROOT/afon.sh" --quiet ${AFON_HOME:+--home "$AFON_HOME"} -a stack-needs -- "$cloud" "$stack" $component 2>&1) \
      || { echo "$output" >&2; return 1; }
  elif [ -x "${AFON_CLI_DIR:-}/afon-cli.sh" ]; then
    output=$("$AFON_CLI_DIR/afon-cli.sh" stack-needs "$cloud" "$stack" $component 2>&1) || { echo "$output" >&2; return 1; }
  else
    echo "-- Cannot find out what stack '$stack' needs: run this script with afon-cli.sh or afon.sh" >&2
    return 1
  fi
  while IFS= read -r line; do
    case "$line" in
      AFON_STACK_*=*) export "${line%%=*}=${line#*=}" ;;
    esac
  done <<< "$output"
  if [ "$stack" != "none" ] && [ -z "${AFON_STACK_ID:-}" ]; then
    echo "-- Cannot find out what stack '$stack' needs: ${output:-no answer}" >&2
    return 1
  fi
  return 0
}
