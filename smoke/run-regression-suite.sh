#!/usr/bin/env bash
#
# Runs a regression suite of the ops.smoke tests against the deployed stacks, optionally deploying them first.
#
# usage: smoke/run-regression-suite.sh [--env|-e <env>] [--deploy|-d <stacks>] [--suite|-s <file>] [--tests|-t <tests>]
#                                      [--list|-l] [--help|-h]
#   --env     the afon env, selecting both the config and cdktf envs, as for './afon.sh --env <env>':
#             $AFON_HOME/config-<env> and $AFON_HOME/cdktf-<env> are symlinked to $AFON_HOME/config and
#             $AFON_HOME/cdktf.  Exported as AFON_ENV, so that every afon.sh command uses it.
#   --deploy  the comma-separated stacks to deploy before the tests, in order, eg aws-full,azure-polaris,gcp-polaris,
#             each with './afon.sh -a manage-stack deploy <stack>'.  The tests are not run if a deploy fails.
#   --suite   the CSV file of the tests (default: smoke/regression-suite.csv)
#   --tests   the comma-separated names of the tests to run (default: all), as listed by --list
#   --list    lists the tests and exits
#
# The tests are read from the suite file, run in its order.  Its first line is the header
# 'name|class|args', and each other line a test: its name, its class, relative to the health.emrys.afon package,
# and its args, if any, separated by spaces.  Fields are separated by '|', so that args may contain commas.
# Blank lines and lines starting with '#' are ignored.
#
# Each test is run with './afon.sh -a exec -ec <class> -ea <args>', and passes if it exits zero.  All the tests
# are run, even if some fail, and their output is written to smoke/target/regression/<test>.log.  The script
# exits non-zero if any test fails.
#
# The tests run against the cluster of the current kubectl context, which must be the AWS full stack's: the
# Spark Connect tests read the azure and gcp catalogs through its Spark Connect server.  --env does not change
# the kubectl context.  The afon.sh env vars apply, eg AFON_HOME, AFON_ENV or AFON_LOGGING_CONSOLE.
#
# As switching env re-points the shared $AFON_HOME symlinks, do not run another afon command on a different env
# while the suite runs.

set -euo pipefail

AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$AFON_ROOT/smoke/target/regression"
DEFAULT_SUITE_FILE="$AFON_ROOT/smoke/regression-suite.csv"
# Turns off the Maven loggers reporting a test's non-zero exit, as the exec plugin's stack trace and Maven's
# failure summary, the test's own output being what tells why it failed.
QUIET_MAVEN_OPTS="-Dorg.slf4j.simpleLogger.log.org.codehaus.mojo.exec=off -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.MavenCli=off"

usage() {
  sed -n '3,/^$/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
}

# Reads the tests of the suite file $1 into TESTS, as its "<name>|<class>|<args>" lines, exiting if there are none,
# or if any has no name or class.
load_tests() {
  local suite_file="$1"
  if [ ! -f "$suite_file" ]; then
    echo "Missing tests file $suite_file" >&2
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
      echo "No name or class in line $number of $suite_file: $line" >&2
      exit 1
    fi
    TESTS+=("$line")
  done < "$suite_file"
  if [ ${#TESTS[@]} -eq 0 ]; then
    echo "No tests in $suite_file" >&2
    exit 1
  fi
}

# Prints field $2 of test $1, 1 being its name, 2 its class and 3 its args.
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
    echo "Option $1 requires a value" >&2
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
      echo "Unknown test: $name.  The tests are:" >&2
      list_tests >&2
      exit 1
    fi
  done
}

# Selects the env $1, if given, for every afon.sh command, exiting if it has no config dir.
use_env() {
  if [ -z "$1" ]; then
    return
  fi
  AFON_HOME="${AFON_HOME:-$HOME/.afon}"
  if [ ! -d "$AFON_HOME/config-$1" ]; then
    echo "Unknown env: $1 (no config dir $AFON_HOME/config-$1)" >&2
    exit 1
  fi
  echo "Using env $1"
  export AFON_ENV="$1"
}

# Deploys the comma-separated stacks $1, in order, exiting if any fails.
deploy_stacks() {
  local stack
  for stack in ${1//,/ }; do
    echo "=== Deploying stack $stack"
    if ! ./afon.sh -a manage-stack deploy "$stack"; then
      echo "Failed to deploy stack $stack: not running the tests" >&2
      exit 1
    fi
  done
}

DEPLOY=
ENV=
LIST=
SELECTED=
SUITE_FILE="$DEFAULT_SUITE_FILE"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env|-e)
      require_value "$1" "${2:-}"; ENV="$2"; shift 2 ;;
    --deploy|-d)
      require_value "$1" "${2:-}"; DEPLOY="$2"; shift 2 ;;
    --suite|-s)
      require_value "$1" "${2:-}"; SUITE_FILE="$2"; shift 2 ;;
    --tests|-t)
      require_value "$1" "${2:-}"; SELECTED="$2"; shift 2 ;;
    --list|-l)
      LIST=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2; exit 1 ;;
  esac
done

load_tests "$SUITE_FILE"
if [ -n "$LIST" ]; then
  list_tests; exit 0
fi

validate_tests "$SELECTED"

use_env "$ENV"

cd "$AFON_ROOT"

deploy_stacks "$DEPLOY"

mkdir -p "$LOG_DIR"
PASSED=()
FAILED=()
for test_line in "${TESTS[@]}"; do
  name=$(test_field "$test_line" 1)
  class=$(test_field "$test_line" 2)
  args=$(test_field "$test_line" 3)
  is_selected "$name" || continue
  log="$LOG_DIR/$name.log"
  echo "=== Running test $name: $class $args"
  exec_args=(-a exec -ec "$class")
  if [ -n "$args" ]; then
    exec_args+=(-ea "$args")
  fi
  if MAVEN_OPTS="${MAVEN_OPTS:-} $QUIET_MAVEN_OPTS" ./afon.sh --mvn-quiet -lc INFO "${exec_args[@]}" 2>&1 | tee "$log"; then
    PASSED+=("$name")
  else
    FAILED+=("$name")
  fi
done

echo
echo "=== Regression summary (logs in $LOG_DIR)"
# Empty arrays are guarded, as expanding them fails under set -u in bash 3.2, the macOS default.
if [ ${#PASSED[@]} -gt 0 ]; then
  for name in "${PASSED[@]}"; do
    echo "PASSED  $name"
  done
fi
if [ ${#FAILED[@]} -gt 0 ]; then
  for name in "${FAILED[@]}"; do
    echo "FAILED  $name"
  done
fi
echo "${#PASSED[@]} passed, ${#FAILED[@]} failed"
[ ${#FAILED[@]} -eq 0 ]
