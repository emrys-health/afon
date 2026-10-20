#!/usr/bin/env bash
#
# Runs a regression suite of the ops.smoke tests against the deployed stacks, optionally setting them up first.
#
# usage: smoke/run-regression-suite.sh [--cli] [--env|-e <env>] [--setup <stacks> [--teardown]] [--suite|-s <file>]
#                                      [--tests|-t <tests>] [--restrict-polaris]
#                                      [--logging-console|-lc <level>] [--list|-l] [--help|-h] [<cloud>]
#   <cloud>   the cloud of the full stack to run the tests against, aws or azure (default: aws), ie <cloud>-full.  The
#             kubectl context is set to the cluster of that stack, with the afon command '<cloud>-update-kube-config
#             tre', and the tests are run with TRE_CLOUD=<cloud>, as the cloud of the stack hosting the TRE.
#   --cli     runs the afon commands with afon-cli.sh, rather than afon.sh: that of the cli distribution in
#             $AFON_CLI_DIR, if set, else in $AFON_HOME/cli.  Implied when the script runs from a cli distribution,
#             eg as $AFON_CLI_DIR/smoke/run-regression-suite.sh, which, being independent of the source tree, has
#             no afon.sh: the default afon-cli.sh is then that of the distribution.  Each command <command> <args>
#             is run as 'afon-cli.sh <command> <args>', or else as './afon.sh -a <command> -- <args>'.
#   --env     the afon env, selecting both the config and cdktf envs, as for './afon.sh --env <env>':
#             $AFON_HOME/config-<env> and $AFON_HOME/cdktf-<env> are symlinked to $AFON_HOME/config and
#             $AFON_HOME/cdktf.  Exported as AFON_ENV, so that every afon command uses it.
#   --setup   the comma-separated stacks to set up, ie deploy and configure, before the tests, in order, eg
#             aws-full,azure-polaris,gcp-polaris, each with the afon command 'manage-stack setup <stack>'.  The tests
#             are not run if a setup fails.  The stacks, including one whose setup failed, are left up, the commands
#             deleting them being printed on exit, unless --teardown is given.
#   --teardown
#             tears down the set up stacks on exit, in reverse order, each with the afon command
#             'manage-stack delete <stack>', whether the tests passed or failed, or a setup failed.  Never done if
#             the script is interrupted, eg with Ctrl-C, which may stop a setup part way: the commands deleting the
#             stacks are printed instead.
#   --suite   the CSV file of the tests (default: smoke/regression-full.csv)
#   --tests   the comma-separated names of the tests to run (default: all), as listed by --list
#   --restrict-polaris
#             restricts the Polaris ingress for the run, for the network smoke test to pass on a stack whose deploy
#             did not, see below.  Changes the cluster, so it is opt-in: it records how the ingress is, ie its
#             annotation, and on Azure the ingress controller's externalTrafficPolicy, in
#             smoke/target/regression/polaris-ingress-snapshot-<yyyymmdd-hhmmss>.txt, then restores exactly that on exit,
#             before any teardown, whatever the properties say.  The file is kept as a record of the run, with the
#             outcome of the restore.  A run killed before it restored, eg with kill -9, leaves a file without an
#             outcome, which the next run restores first.  Not done if no test is the network smoke test.
#   --logging-console
#             the logging console level of every afon command, one of OFF|DEBUG|INFO|WARN|ERROR (default: that of
#             afon.sh, INFO, or of afon-cli.sh, OFF).  Exported as AFON_LOGGING_CONSOLE, which it overrides.
#   --list    lists the tests and exits
#
# The tests are read from the suite file, run in its order.  Its first line is the header 'name|command|args', and
# each other line a test: its name, its afon command, eg network-smoke for the ops class NetworkSmokeMain, and the
# command's args, if any, separated by spaces.
# With afon.sh, the commands are checked against the afon-ops *Main
# classes of the source tree when the suite file is read, as afon.sh exits zero on an unknown command.
# With --cli, afon-cli.sh resolves them, failing a test whose command is unknown.
# Fields are separated by '|', so that args may contain commas. Blank lines and lines starting with '#' are ignored.
#
# Each test is run as an afon command, and passes if it exits zero.  All the tests are run, even if some fail.
# Their logging goes to the afon log file, $AFON_HOME/logs/afon-core.log, and to the console as set by
# --logging-console.  The script exits non-zero if any test fails.  The results of each run, ie its settings, the
# times and outcomes of its stack setups, tests and teardowns, and its summary, are also written, without any
# logging, to smoke/target/regression/results-<yyyymmdd-hhmmss>.txt.
#
# The tests run against the full stack's cluster of the <cloud>: the Spark Connect tests read the catalogs of the
# clouds through its Spark Connect server.  After any setups, the current kubectl context is set to the full stack
# cluster of the env, that of --env, if given, else that of the current config, with the afon command
# '<cloud>-update-kube-config tre', ie the stack with the role tre, and left so.  The stacks are tested as deployed:
# eg the network smoke test, which checks that Polaris cannot be reached from outside of the cluster, only passes if
# the deploy restricted its ingress, ie with AWS_POLARIS_ALLOWED_CIDRS or AZURE_POLARIS_ALLOWED_CIDRS set.  The suite
# changes nothing in the cluster, unless given --restrict-polaris: it only checks, before the tests, that the ingress is
# restricted, and if it is not, warns that the network smoke test is expected to fail, and prints the kubectl commands
# that restrict it for the cloud: the configured CIDRs plus the cluster's own addresses, ie its egress IP on AWS, or its
# outbound IP and pod CIDR on Azure, with the ingress controller keeping the callers' IPs.  The machine running the
# tests must not be among the CIDRs, else the call to Polaris from outside of the cluster gets through.
# The env vars of afon.sh and afon-cli.sh apply, eg AFON_HOME, AFON_ENV or AFON_LOGGING_CONSOLE.
#
# As switching env re-points the shared $AFON_HOME symlinks, and the current context of the shared ~/.kube/config,
# do not run another afon or kubectl command on a different env or cluster while the suite runs.

set -euo pipefail

AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SUITE_FILE="$AFON_ROOT/smoke/regression-full.csv"
OPS_JAVA_DIR="$AFON_ROOT/afon-ops/src/main/java/health/emrys/afon/ops"
# The dir of the results files, one per run, eg results-<yyyymmdd-hhmmss>.txt.
RESULTS_DIR="$AFON_ROOT/smoke/target/regression"
# Turns off the Maven loggers of afon.sh reporting a test's non-zero exit, as the exec plugin's stack trace and
# Maven's failure summary, the test's own output being what tells why it failed.
QUIET_MAVEN_OPTS="-Dorg.slf4j.simpleLogger.log.org.codehaus.mojo.exec=off -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.MavenCli=off"
# The script's own messages are coloured as logback's %highlight colours the levels (see logback.xml): the
# banners as INFO, the warnings as WARN and the errors, and the failed tests, as ERROR, each stream only if it is
# a terminal.  The passed tests are in green, which logback has no level for.
INFO_COLOR=
SUCCESS_COLOR=
RESET_COLOR=
if [ -t 1 ]; then
  INFO_COLOR=$(printf '\033[34m')
  SUCCESS_COLOR=$(printf '\033[32m')
  RESET_COLOR=$(printf '\033[0;39m')
fi
STDERR_WARN_COLOR=
STDERR_ERROR_COLOR=
STDERR_RESET_COLOR=
if [ -t 2 ]; then
  STDERR_WARN_COLOR=$(printf '\033[31m')
  STDERR_ERROR_COLOR=$(printf '\033[1;31m')
  STDERR_RESET_COLOR=$(printf '\033[0;39m')
fi

# Prints the banner "=== <args>", in logback's INFO colour if the output is a terminal.
banner() {
  echo "${INFO_COLOR}=== $*${RESET_COLOR}"
}

# Prints the banner "=== <args>" of a success, eg a passed test, in green if the output is a terminal.
success() {
  echo "${SUCCESS_COLOR}=== $*${RESET_COLOR}"
}

# Prints the warning "WARNING: <args>" to stderr, in logback's WARN colour if stderr is a terminal.
warning() {
  echo "${STDERR_WARN_COLOR}WARNING: $*${STDERR_RESET_COLOR}" >&2
}

# Prints the error "ERROR: <args>" to stderr, in logback's ERROR colour if stderr is a terminal: an error either
# stops the script or fails a test, while the script carries on as before after a warning.
error() {
  echo "${STDERR_ERROR_COLOR}ERROR: $*${STDERR_RESET_COLOR}" >&2
}

# Starts the results file of this run, RESULTS_FILE, with the run's settings.  It holds the results of the run, as
# record writes them, without the logging of the commands, which the console may also show, eg with
# --logging-console INFO.
start_results() {
  mkdir -p "$RESULTS_DIR"
  RESULTS_FILE="$RESULTS_DIR/results-$(date +%Y%m%d-%H%M%S).txt"
  {
    echo "Regression suite run of $(date '+%Y-%m-%d %H:%M:%S')"
    echo "env: $(current_env)"
    echo "cloud: $CLOUD (stack $CLOUD-full)"
    echo "suite: $SUITE_FILE"
    echo "tests: ${SELECTED:-all}"
    echo "stacks set up: ${SETUP:-none}${TEARDOWN:+, then torn down}"
    if [ -n "$RESTRICT_POLARIS" ]; then
      echo "polaris ingress: restricted for the run, then restored"
    else
      echo "polaris ingress: as deployed"
    fi
    if [ -n "$CLI" ]; then
      echo "commands run with: $(cli_script)"
    else
      echo "commands run with: $AFON_ROOT/afon.sh"
    fi
    echo
  } > "$RESULTS_FILE"
}

# Prints the time elapsed since $1, a value of SECONDS, in whole seconds, as mm:ss, eg 17:01, or as hh:mm:ss, eg
# 01:17:01, from an hour.
elapsed_since() {
  local elapsed=$((SECONDS - $1))
  if [ $elapsed -ge 3600 ]; then
    printf '%02d:' $((elapsed / 3600))
  fi
  printf '%02d:%02d' $((elapsed % 3600 / 60)) $((elapsed % 60))
}

# Records and prints the time the whole run took, from the start of the script, including any setup and teardown:
# as completed, or else as interrupted, given $1.  Then prints the results file, last, so it is easy to find.
finish_run() {
  local outcome="completed in"
  if [ -n "${1:-}" ]; then
    outcome="interrupted after"
  fi
  record "regression suite $outcome $(elapsed_since 0)"
  banner "Regression suite $outcome $(elapsed_since 0)"
  banner "Results in $RESULTS_FILE"
}

# Appends the result $* to the results file, once started.
record() {
  if [ -n "$RESULTS_FILE" ]; then
    echo "$*" >> "$RESULTS_FILE"
  fi
}

# Prints the afon log file, which logback.xml writes to in afon home.
afon_log_file() {
  echo "${AFON_HOME:-$HOME/.afon}/logs/afon-core.log"
}

# Whether the script runs from a cli distribution, ie its parent dir has afon-cli.sh, but no afon.sh, as the
# distribution is independent of the source tree.
from_cli_distribution() {
  [ -x "$AFON_ROOT/afon-cli.sh" ] && [ ! -e "$AFON_ROOT/afon.sh" ]
}

# Prints the afon-cli.sh that --cli runs: that of the cli distribution in AFON_CLI_DIR, if set, else that of the
# distribution the script runs from, if any, else that in $AFON_HOME/cli.
cli_script() {
  local default_dir="${AFON_HOME:-$HOME/.afon}/cli"
  if from_cli_distribution; then
    default_dir="$AFON_ROOT"
  fi
  echo "${AFON_CLI_DIR:-$default_dir}/afon-cli.sh"
}

# Sets AFON_COMMAND to the command line running the afon command $1, eg manage-stack, with the args $2...: with
# afon-cli.sh given --cli, else with afon.sh, quietly, any args following '--', so that afon.sh passes them all on
# to the command.  The one place building it, so that what run_afon runs is what afon_command_line prints.
afon_command() {
  local command="$1"
  shift
  if [ -n "$CLI" ]; then
    AFON_COMMAND=("$(cli_script)" "$command" "$@")
  else
    # TRE_CLOUD, the cloud of the stack hosting the TRE, is that of the tests, whatever the config says
    AFON_COMMAND=("$AFON_ROOT/afon.sh" -q -a "$command" -ee "TRE_CLOUD=$CLOUD")
    if [ $# -gt 0 ]; then
      AFON_COMMAND+=(-- "$@")
    fi
  fi
}

# Runs the afon command $1 with the args $2..., as afon_command builds it.
run_afon() {
  afon_command "$@"
  "${AFON_COMMAND[@]}"
}

# Prints the command line running the afon command $1 with the args $2..., as run_afon runs it, for the user.
afon_command_line() {
  afon_command "$@"
  echo "${AFON_COMMAND[*]}"
}

# Whether $1 is an afon ops command of the source tree, ie the kebab-case name of an afon-ops *Main class, eg
# network-smoke for NetworkSmokeMain, as afon.sh resolves it, case-insensitively.  Checked up front with afon.sh,
# as it exits zero on an unknown command, but not with afon-cli.sh, which resolves the commands in its JVM,
# independently of the source tree, failing on an unknown one.
is_ops_command() {
  local class
  class=$(echo "$1" | awk -F- '{ for (i = 1; i <= NF; i++) printf "%s", toupper(substr($i, 1, 1)) substr($i, 2) }')
  [ -n "$(find "$OPS_JAVA_DIR" -iname "${class}Main.java" -not -path '*/ops/scripts/*' 2>/dev/null | head -1)" ]
}

usage() {
  sed -n '3,/^$/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
}

# Reads the tests of the suite file $1 into TESTS, as its "<name>|<command>|<args>" lines, exiting if there are
# none, or if any has no name or command, or, with afon.sh, an unknown command.
load_tests() {
  local suite_file="$1"
  if [ ! -f "$suite_file" ]; then
    error "Missing tests file $suite_file"
    exit 1
  fi
  TESTS=()
  local line number=0 header=
  while IFS= read -r line || [ -n "$line" ]; do
    number=$((number + 1))
    line=$(echo "$line" | tr -d '\r')
    case "$line" in
      ''|'#'*) continue ;;
    esac
    if [ -z "$header" ]; then
      header="$line"
      continue
    fi
    if [ -z "$(test_field "$line" 1)" ] || [ -z "$(test_field "$line" 2)" ]; then
      error "No name or command in line $number of $suite_file: $line"
      exit 1
    fi
    if [ -z "$CLI" ] && ! is_ops_command "$(test_field "$line" 2)"; then
      error "Unknown command in line $number of $suite_file, with no afon-ops *Main class: $line"
      exit 1
    fi
    TESTS+=("$line")
  done < "$suite_file"
  if [ ${#TESTS[@]} -eq 0 ]; then
    error "No tests in $suite_file"
    exit 1
  fi
}

# Prints field $2 of test $1, 1 being its name, 2 its command and 3 its args.
test_field() {
  if [ "$2" -eq 3 ]; then
    echo "$1" | cut -d'|' -f3-
  else
    echo "$1" | cut -d'|' -f"$2"
  fi
}

list_tests() {
  local test_line
  for test_line in "${TESTS[@]}"; do
    printf '%-20s %s %s\n' "$(test_field "$test_line" 1)" "$(test_field "$test_line" 2)" "$(test_field "$test_line" 3)"
  done
}

test_names() {
  local test_line
  for test_line in "${TESTS[@]}"; do
    test_field "$test_line" 1
  done
}

# Exits if option $1 has no value $2.
require_value() {
  if [ -z "$2" ]; then
    error "Option $1 requires a value"
    usage >&2; exit 1
  fi
}

# Whether test $1 is among the selected tests, all being selected if none are.
is_selected() {
  [ -z "$SELECTED" ] || [[ ",$SELECTED," == *",$1,"* ]]
}

# Exits if any of the comma-separated tests $1 is unknown.
validate_tests() {
  local name
  for name in ${1//,/ }; do
    if ! test_names | grep -x "$name" >/dev/null; then
      error "Unknown test: $name.  The tests are:"
      list_tests >&2
      exit 1
    fi
  done
}

# Selects the env $1, if given, for every afon command, exiting if it has no config dir.
use_env() {
  if [ -z "$1" ]; then
    return
  fi
  AFON_HOME="${AFON_HOME:-$HOME/.afon}"
  if [ ! -d "$AFON_HOME/config-$1" ]; then
    error "Unknown env: $1 (no config dir $AFON_HOME/config-$1)"
    exit 1
  fi
  echo "Using env $1"
  export AFON_ENV="$1"
}

# Prints the env the afon commands run in: that of AFON_ENV, which use_env exports given --env, else that of the
# current config, ie the env of the config-<env> dir $AFON_HOME/config links to, if any.
current_env() {
  local config_dir
  if [ -n "${AFON_ENV:-}" ]; then
    echo "$AFON_ENV"
    return
  fi
  config_dir=$(readlink "${AFON_HOME:-$HOME/.afon}/config" 2>/dev/null || true)
  case "$(basename "$config_dir")" in
    config-*) basename "$config_dir" | sed 's/^config-//' ;;
    *) echo "of the current config" ;;
  esac
}

# Makes the full stack cluster of the $CLOUD of the current env the current kubectl context, exiting if it fails.
update_kube_config() {
  banner "Updating the kubectl context to the $CLOUD full stack cluster of env $(current_env)"
  if ! run_afon "$CLOUD-update-kube-config" tre; then
    error "Failed to update the kubectl context for env $(current_env): not running the tests"
    exit 1
  fi
}

# The reading of the Polaris ingress, and the CIDRs to restrict it to, shared with kube/polaris-whitelist.sh
source "$AFON_ROOT/scripts/kube/polaris-whitelist-source.sh"


# The snapshots of the Polaris ingress that --restrict-polaris takes before changing it, one file per run,
# polaris-ingress-snapshot-<yyyymmdd-hhmmss>.txt, like the results files, one key=value per line.  Kept in files rather
# than in memory, so that a run killed before it restored the ingress, eg with kill -9, leaves what is needed to restore
# it, which the next run does first.  They are kept after the restore as a record of the run, which gives the file an
# outcome= line: only a file without one is still to be restored.  SNAPSHOT_FILE is that of this run, once it is taken.
SNAPSHOT_PREFIX="$RESULTS_DIR/polaris-ingress-snapshot-"
SNAPSHOT_FILE=

# Whether a selected test of the suite is the network smoke test, which is what needs the Polaris ingress restricted.
runs_network_smoke() {
  local test_line
  for test_line in "${TESTS[@]}"; do
    if [ "$(test_field "$test_line" 2)" = "network-smoke" ] && is_selected "$(test_field "$test_line" 1)"; then
      return 0
    fi
  done
  return 1
}

# Checks, without changing the cluster, that the Polaris ingress of the current kubectl context is restricted, as the
# network smoke test, which checks that Polaris cannot be reached from outside of the cluster, needs.  If it is not,
# warns that the test is expected to fail, and prints the commands that restrict the ingress, for the user to run, or the
# --restrict-polaris option, which runs them for the duration of the suite.  The tests run either way.
check_polaris_ingress() {
  local context annotation state controller policy cidrs
  context=$(kubectl config current-context)
  annotation=$(polaris_annotation)
  state=$(ingress_annotation "$annotation" "$context")
  case "$state" in
    missing)
      warning "no Polaris ingress in kubectl context $context: the network smoke test is expected to fail"
      record "Polaris ingress: none found"
      return 0 ;;
    present:?*)
      banner "The Polaris ingress is restricted to ${state#present:}"
      record "Polaris ingress restricted to ${state#present:}"
      if [ "$CLOUD" = "azure" ]; then
        controller=$(controller_service "$context")
        policy=
        if [ -n "$controller" ]; then
          policy=$(kubectl --context "$context" get "$controller" -n ingress-nginx \
                     -o jsonpath='{.spec.externalTrafficPolicy}' 2>/dev/null || true)
        fi
        if [ "$policy" != "Local" ]; then
          warning "the ingress controller does not keep the callers' IPs (externalTrafficPolicy '${policy:-unknown}'," \
                  "not Local): its restriction is not effective, so the network smoke test is expected to fail"
          echo "Set it with:"
          echo "  kubectl --context $context patch ${controller:-<the ingress-nginx controller service>}" \
               "-n ingress-nginx --type merge -p '{\"spec\":{\"externalTrafficPolicy\":\"Local\"}}'"
        fi
      fi
      return 0 ;;
  esac
  warning "the Polaris ingress is not restricted ($annotation is not set):"$'\n'"the network smoke test, which checks that" \
          "Polaris cannot be reached from outside of the cluster, is expected to fail"
  record "Polaris ingress: not restricted"
  cidrs=$(restriction_cidrs)
  echo "To restrict it, redeploy with $(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')_POLARIS_ALLOWED_CIDRS set to the callers to admit (which must not include this machine)"
  echo "or restrict the live ingress, until the next deploy, with:"
  if [ -n "$cidrs" ]; then
    print_restrict_commands "$context" "$cidrs"
  fi
  echo "or, with the same CIDRs: afon-cli.sh kube-polaris-whitelist $CLOUD set"
  echo "or run the suite with --restrict-polaris, which restricts the ingress for the run and restores it after to its exact state."
}

# Prints the value of key $1 of the snapshot of the Polaris ingress.
snapshot_value() {
  grep "^$1=" "$SNAPSHOT_FILE" | head -1 | cut -d= -f2-
}

# Whether the snapshot file $1 is still to be restored, ie it has no outcome.
snapshot_pending() {
  [ -f "$1" ] && ! grep -q "^outcome=" "$1"
}

# Prints the snapshot files that are still to be restored, ie of runs that did not restore the ingress, oldest first.
pending_snapshots() {
  local file
  for file in "${SNAPSHOT_PREFIX}"*.txt; do
    if snapshot_pending "$file"; then
      echo "$file"
    fi
  done
}

# Records in the snapshot file of the Polaris ingress what became of it, $1, once it has been restored, or is no longer to be:
# the ingress is gone, or was changed since the suite restricted it.  The file is kept, as a record of the run, but is no
# longer pending.
record_snapshot_outcome() {
  {
    echo "outcome=$1"
    echo "finished=$(date '+%Y-%m-%d %H:%M:%S')"
  } >> "$SNAPSHOT_FILE"
}

# Puts the Polaris ingress back as the snapshot of --restrict-polaris recorded it, ie the annotation set to its recorded
# value, or removed if it was not set, and, on Azure, the controller's externalTrafficPolicy as it was, whatever the
# properties say.  Does nothing without a pending snapshot, ie one with no outcome.  If the annotation is no longer what the
# suite set, someone changed it since, and it is left as it is.  The snapshot gets its outcome once the ingress is restored,
# else is left pending for the next run to restore.  It runs on exit, so it only warns on failure, keeping the exit status of
# the script.
restore_polaris_ingress() {
  local context annotation state controller policy applied current
  if [ -z "$SNAPSHOT_FILE" ] || ! snapshot_pending "$SNAPSHOT_FILE"; then
    return 0
  fi
  context=$(snapshot_value context)
  annotation=$(snapshot_value annotation)
  state=$(snapshot_value state)
  controller=$(snapshot_value controller)
  policy=$(snapshot_value policy)
  applied=$(snapshot_value applied)
  if ! kubectl --context "$context" get ingress polaris-ingress -n default >/dev/null 2>&1; then
    # eg torn down since, so nothing to restore
    record_snapshot_outcome "not restored, as the ingress is gone"
    return 0
  fi
  current=$(ingress_annotation "$annotation" "$context")
  if [ "$current" != "present:$applied" ] && [ "$current" != "$state" ]; then
    warning "the Polaris ingress was changed since the suite restricted it (now $current, not present:$applied):" \
            "not restoring it to $state"
    record_snapshot_outcome "not restored, as the annotation was changed since the suite set it (now $current)"
    return 0
  fi
  banner "Restoring the Polaris ingress of $context to how it was: $state"
  case "$state" in
    present:*)
      if ! kubectl --context "$context" annotate ingress polaris-ingress -n default --overwrite \
             "$annotation=${state#present:}" >/dev/null; then
        warning "failed to restore the Polaris ingress: left pending in $SNAPSHOT_FILE, which the next run restores"
        return 0
      fi ;;
    *)
      if ! kubectl --context "$context" annotate ingress polaris-ingress -n default "$annotation-" >/dev/null; then
        warning "failed to restore the Polaris ingress: left pending in $SNAPSHOT_FILE, which the next run restores"
        return 0
      fi ;;
  esac
  if [ -n "$controller" ] && [ -n "$policy" ]; then
    if ! kubectl --context "$context" patch "$controller" -n ingress-nginx --type merge \
           -p "{\"spec\":{\"externalTrafficPolicy\":\"$policy\"}}" >/dev/null; then
      warning "failed to restore externalTrafficPolicy $policy of $controller: left pending in $SNAPSHOT_FILE, which the next run restores"
      return 0
    fi
  fi
  record_snapshot_outcome "restored"
}

# For --restrict-polaris: restricts the Polaris ingress of the current kubectl context to the restriction_cidrs, which the
# network smoke test needs, for the run, first recording how it is in a new snapshot file, to restore it on exit with
# restore_polaris_ingress, as a run killed before it did leaves the ingress restricted: the pending snapshots of those
# runs are restored first.  Exits if the ingress cannot be restricted, and skips it, with a warning, if there are no CIDRs.
restrict_polaris_ingress() {
  local context annotation state controller= policy= cidrs pending
  for pending in $(pending_snapshots); do
    warning "a previous run did not restore the Polaris ingress: restoring it first, from $pending"
    SNAPSHOT_FILE="$pending"
    restore_polaris_ingress
    if snapshot_pending "$pending"; then
      error "Could not restore the Polaris ingress from $pending: not running the tests"
      exit 1
    fi
  done
  SNAPSHOT_FILE=
  context=$(kubectl config current-context)
  annotation=$(polaris_annotation)
  state=$(ingress_annotation "$annotation" "$context")
  if [ "$state" = "missing" ]; then
    error "No Polaris ingress in kubectl context $context to restrict: not running the tests"
    exit 1
  fi
  if [ "$CLOUD" = "azure" ]; then
    controller=$(controller_service "$context")
    if [ -z "$controller" ]; then
      error "No ingress controller service in kubectl context $context: not running the tests"
      exit 1
    fi
    policy=$(kubectl --context "$context" get "$controller" -n ingress-nginx \
               -o jsonpath='{.spec.externalTrafficPolicy}' 2>/dev/null || true)
  fi
  cidrs=$(restriction_cidrs)
  if [ -z "$cidrs" ]; then
    warning "no CIDRs to restrict the Polaris ingress to: leaving it as it is"
    return 0
  fi
  mkdir -p "$RESULTS_DIR"
  SNAPSHOT_FILE="${SNAPSHOT_PREFIX}$(date +%Y%m%d-%H%M%S).txt"
  {
    echo "taken=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "context=$context"
    echo "annotation=$annotation"
    echo "state=$state"
    echo "controller=$controller"
    echo "policy=$policy"
    echo "applied=$cidrs"
  } > "$SNAPSHOT_FILE"
  banner "Restricting the Polaris ingress of $context to $cidrs, to restore it as it is now ($state) on exit"
  record "Polaris ingress restricted for the run to $cidrs, to be restored to $state, as recorded in $SNAPSHOT_FILE"
  if [ "$CLOUD" = "azure" ]; then
    if ! kubectl --context "$context" patch "$controller" -n ingress-nginx --type merge \
           -p '{"spec":{"externalTrafficPolicy":"Local"}}'; then
      error "Failed to set externalTrafficPolicy Local on the ingress controller: not running the tests"
      exit 1
    fi
  fi
  if ! kubectl --context "$context" annotate ingress polaris-ingress -n default --overwrite "$annotation=$cidrs"; then
    error "Failed to restrict the Polaris ingress: not running the tests"
    exit 1
  fi
}

# Sets up the comma-separated stacks $1, in order, exiting if any fails.  Each stack is recorded in SETUP_STACKS
# before being set up, so that a stack whose setup failed part way is also handled by finish_stacks, which is run
# on exit, however the script exits.
setup_stacks() {
  local stack start result
  if [ -z "$1" ]; then
    return
  fi
  for stack in ${1//,/ }; do
    SETUP_STACKS+=("$stack")
    banner "Setting up stack $stack"
    start=$SECONDS
    if ! run_afon manage-stack setup "$stack"; then
      result="stack $stack failed to set up in $(elapsed_since $start)"
      record "$result: tests not run"
      error "Stack ${result#stack }: not running the tests"
      exit 1
    fi
    result="stack $stack set up in $(elapsed_since $start)"
    record "$result"
    success "Stack ${result#stack }"
  done
}

# Prints the stacks of SETUP_STACKS in reverse order, the order in which to tear them down.
reversed_setup_stacks() {
  local index
  index=$((${#SETUP_STACKS[@]} - 1))
  while [ $index -ge 0 ]; do
    echo "${SETUP_STACKS[$index]}"
    index=$((index - 1))
  done
}

# Prints the commands deleting the stacks of SETUP_STACKS, in the order in which to run them.
print_delete_commands() {
  local stack
  for stack in $(reversed_setup_stacks); do
    echo "  $(afon_command_line manage-stack delete "$stack")"
  done
}

# Tears down the stacks of SETUP_STACKS, in reverse order, with the afon command 'manage-stack delete <stack>',
# trying all of them, and returning non-zero if any fails.
teardown_stacks() {
  local stack start result failed=0
  for stack in $(reversed_setup_stacks); do
    banner "Tearing down stack $stack"
    start=$SECONDS
    if run_afon manage-stack delete "$stack"; then
      result="stack $stack torn down in $(elapsed_since $start)"
      record "$result"
      success "Stack ${result#stack }"
    else
      result="stack $stack failed to tear down in $(elapsed_since $start):"
      result="$result delete it with '$(afon_command_line manage-stack delete "$stack")'"
      record "$result"
      warning "Stack ${result#stack }"
      failed=1
    fi
  done
  return $failed
}

# The EXIT trap of the script, which finishes any set up stacks, keeping the exit status, unless a teardown fails.  Never tears down the set up
# stacks if the script was interrupted, as a setup may then have been stopped part way, with its tofu state still
# locked, and a teardown would take as long as the setup: it only says how to delete them.  Else it tears them
# down if --teardown is given, or says how to delete them.
finish_stacks() {
  local status=$?
  trap - EXIT
  # before any teardown, while the ingress is there
  restore_polaris_ingress
  if [ ${#SETUP_STACKS[@]} -eq 0 ]; then
    exit $status
  fi
  if [ -n "$INTERRUPTED" ]; then
    record "Interrupted: the stacks were not torn down"
    error "Interrupted: not tearing down the set up stacks, which may be part way set up.  Delete them with:"
    print_delete_commands >&2
  elif [ -n "$TEARDOWN" ]; then
    if ! teardown_stacks; then
      status=1
    fi
  else
    record "Stacks left up, as --teardown is not given"
    echo "Leaving the set up stacks up, as --teardown is not given.  Delete them with:"
    print_delete_commands
  fi
  finish_run "$INTERRUPTED"
  exit $status
}

AFON_COMMAND=()
CLI=
RESULTS_FILE=
SETUP=
SETUP_STACKS=()
INTERRUPTED=
TEARDOWN=
RESTRICT_POLARIS=
ENV=
LIST=
LOGGING_CONSOLE=
SELECTED=
SUITE_FILE="$DEFAULT_SUITE_FILE"
CLOUD=aws

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cli)
      CLI=true; shift ;;
    --env|-e)
      require_value "$1" "${2:-}"; ENV="$2"; shift 2 ;;
    --setup)
      require_value "$1" "${2:-}"; SETUP="$2"; shift 2 ;;
    --teardown)
      TEARDOWN=true; shift ;;
    --restrict-polaris)
      RESTRICT_POLARIS=true; shift ;;
    --suite|-s)
      require_value "$1" "${2:-}"; SUITE_FILE="$2"; shift 2 ;;
    --tests|-t)
      require_value "$1" "${2:-}"; SELECTED="$2"; shift 2 ;;
    --logging-console|-lc)
      require_value "$1" "${2:-}"; LOGGING_CONSOLE="$2"; shift 2 ;;
    --list|-l)
      LIST=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    -*)
      error "Unknown option: $1"
      usage >&2; exit 1 ;;
    *)
      CLOUD=$(echo "$1" | tr '[:upper:]' '[:lower:]'); shift ;;
  esac
done

case "$CLOUD" in
  aws|azure) ;;
  *)
    error "Unsupported cloud: $CLOUD, which should be aws or azure, the clouds of a full stack"
    usage >&2; exit 1 ;;
esac

if [ -n "$TEARDOWN" ] && [ -z "$SETUP" ]; then
  error "Option --teardown requires --setup"
  usage >&2; exit 1
fi

# Run from a cli distribution, which has no afon.sh, the commands can only be run with afon-cli.sh.
if [ -z "$CLI" ] && from_cli_distribution; then
  CLI=true
fi

if [ -n "$CLI" ] && [ ! -x "$(cli_script)" ]; then
  error "No afon-cli.sh at $(cli_script): install the cli distribution in \$AFON_HOME/cli, or set AFON_CLI_DIR to it"
  exit 1
fi
if [ -z "$CLI" ] && [ ! -x "$AFON_ROOT/afon.sh" ]; then
  error "No afon.sh at $AFON_ROOT, if running from the cli distribution: use --cli"
  exit 1
fi

load_tests "$SUITE_FILE"
if [ -n "$LIST" ]; then
  list_tests; exit 0
fi

validate_tests "$SELECTED"

use_env "$ENV"

if [ -n "$CLI" ]; then
  echo "Running the afon commands with $(cli_script)"
  # Without afon-cli.sh's banner on every command, unless SPRING_MAIN_BANNER_MODE says otherwise.
  export SPRING_MAIN_BANNER_MODE="${SPRING_MAIN_BANNER_MODE:-off}"
  # TRE_CLOUD, the cloud of the stack hosting the TRE, is that of the tests, whatever the config says: afon-cli.sh passes
  # the NAME=VALUE pairs of AFON_ENVS to its java process as system properties, as does afon.sh with --exec-envs
  export AFON_ENVS="${AFON_ENVS:+$AFON_ENVS }TRE_CLOUD=$CLOUD"
fi

if [ -n "$LOGGING_CONSOLE" ]; then
  export AFON_LOGGING_CONSOLE="$LOGGING_CONSOLE"
fi

cd "$AFON_ROOT"

start_results
# On every exit, however the script exits: finishes any set up stacks
trap finish_stacks EXIT
trap 'INTERRUPTED=true; exit 130' INT
trap 'INTERRUPTED=true; exit 143' TERM
setup_stacks "$SETUP"

update_kube_config
if runs_network_smoke; then
  if [ -n "$RESTRICT_POLARIS" ]; then
    restrict_polaris_ingress
  else
    check_polaris_ingress
  fi
fi

# The results of the passed and failed tests, eg "test network passed in 02:11", recorded as they are, and printed
# capitalised, as are those of the stacks, eg "stack aws-full set up in 17:01".
PASSED=()
FAILED=()
for test_line in "${TESTS[@]}"; do
  name=$(test_field "$test_line" 1)
  command=$(test_field "$test_line" 2)
  args=$(test_field "$test_line" 3)
  is_selected "$name" || continue
  banner "Running test $name: $(afon_command_line "$command" $args)"
  start=$SECONDS
  # The args are left unquoted, to split them on spaces.
  if MAVEN_OPTS="${MAVEN_OPTS:-} $QUIET_MAVEN_OPTS" run_afon "$command" $args; then
    result="test $name passed in $(elapsed_since $start)"
    PASSED+=("$result")
    record "$result"
    success "Test ${result#test }"
  else
    result="test $name failed in $(elapsed_since $start)"
    FAILED+=("$result")
    record "$result"
    error "Test ${result#test }: see $(afon_log_file)"
  fi
done

echo
banner "Regression summary (log in $(afon_log_file))"
# Empty arrays are guarded, as expanding them fails under set -u in bash 3.2, the macOS default.
if [ ${#PASSED[@]} -gt 0 ]; then
  for result in "${PASSED[@]}"; do
    success "Test ${result#test }"
  done
fi
if [ ${#FAILED[@]} -gt 0 ]; then
  for result in "${FAILED[@]}"; do
    error "Test ${result#test }"
  done
fi
record "tests: ${#PASSED[@]} passed, ${#FAILED[@]} failed"
banner "Tests: ${#PASSED[@]} passed, ${#FAILED[@]} failed"
# With stacks set up, finish_stacks finishes the run on exit, after any teardown.
if [ ${#SETUP_STACKS[@]} -eq 0 ]; then
  finish_run
fi
[ ${#FAILED[@]} -eq 0 ]
