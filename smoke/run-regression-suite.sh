#!/usr/bin/env bash
#
# Runs a regression suite of the ops.smoke tests against the deployed stacks, optionally deploying them first.
#
# usage: smoke/run-regression-suite.sh [--env|-e <env>] [--deploy|-d <stacks> [--teardown]] [--suite|-s <file>]
#                                      [--tests|-t <tests>] [--logging-console|-lc <level>] [--list|-l] [--help|-h]
#   --env     the afon env, selecting both the config and cdktf envs, as for './afon.sh --env <env>':
#             $AFON_HOME/config-<env> and $AFON_HOME/cdktf-<env> are symlinked to $AFON_HOME/config and
#             $AFON_HOME/cdktf.  Exported as AFON_ENV, so that every afon.sh command uses it.
#   --deploy  the comma-separated stacks to deploy before the tests, in order, eg aws-full,azure-polaris,gcp-polaris,
#             each with './afon.sh -a manage-stack deploy <stack>'.  The tests are not run if a deploy fails.
#             The stacks, including one whose deploy failed, are left up, the commands deleting them being printed
#             on exit, unless --teardown is given.
#   --teardown
#             tears down the deployed stacks on exit, in reverse order, each with
#             './afon.sh -a manage-stack delete <stack>', whether the tests passed or failed, or a deploy failed.
#             Never done if the script is interrupted, eg with Ctrl-C, which may stop a deploy part way: the commands
#             deleting the stacks are printed instead.
#   --suite   the CSV file of the tests (default: smoke/regression-aws.csv)
#   --tests   the comma-separated names of the tests to run (default: all), as listed by --list
#   --logging-console
#             the logging console level of every afon.sh command, one of OFF|DEBUG|INFO|WARN|ERROR (default: the
#             logback configuration's, INFO).  Exported as AFON_LOGGING_CONSOLE, which it overrides.
#   --list    lists the tests and exits
#
# The tests are read from the suite file, run in its order.  Its first line is the header
# 'name|class|args', and each other line a test: its name, its class, relative to the health.emrys.afon package,
# and its args, if any, separated by spaces.  Fields are separated by '|', so that args may contain commas.
# Blank lines and lines starting with '#' are ignored.
#
# Each test is run with './afon.sh -a exec -ec <class> -ea <args>', and passes if it exits zero.  All the tests
# are run, even if some fail.  Their logging goes to the afon log file, $AFON_HOME/logs/afon-core.log, and to the
# console as set by --logging-console.  The script exits non-zero if any test fails.
#
# The tests run against the cluster of the current kubectl context, which must be the AWS full stack's: the Spark
# Connect tests read the azure and gcp catalogs through its Spark Connect server.  Given --env, the current context
# is set to the env's AWS full stack cluster, after any deploys, with
# './afon.sh -a aws-update-kube-config full -e <env>', and left so.  Its Polaris ingress is then restricted, as the
# network smoke test expects, to the AWS_POLARIS_ALLOWED_CIDRS of afon-aws.properties plus the cluster's egress IP,
# that of its NAT gateway, or by default AWS_EGRESS_IP.
# The afon.sh env vars apply, eg AFON_HOME, AFON_ENV or AFON_LOGGING_CONSOLE.
#
# As switching env re-points the shared $AFON_HOME symlinks, and the current context of the shared ~/.kube/config,
# do not run another afon or kubectl command on a different env or cluster while the suite runs.

set -euo pipefail

AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SUITE_FILE="$AFON_ROOT/smoke/regression-aws.csv"
# Turns off the Maven loggers reporting a test's non-zero exit, as the exec plugin's stack trace and Maven's
# failure summary, the test's own output being what tells why it failed.
QUIET_MAVEN_OPTS="-Dorg.slf4j.simpleLogger.log.org.codehaus.mojo.exec=off -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.MavenCli=off"
# The script's own messages are coloured as logback's %highlight colours the levels (see logback.xml): the
# banners as INFO, the warnings as WARN and the errors, and the failed tests, as ERROR, each stream only if it is
# a terminal.  The passed tests are in green, which logback has no level for.
INFO_COLOR=
SUCCESS_COLOR=
ERROR_COLOR=
RESET_COLOR=
if [ -t 1 ]; then
  INFO_COLOR=$(printf '\033[34m')
  SUCCESS_COLOR=$(printf '\033[32m')
  ERROR_COLOR=$(printf '\033[1;31m')
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

# Prints the afon log file, which logback.xml writes under the afon home.
afon_log_file() {
  echo "${AFON_HOME:-$HOME/.afon}/logs/afon-core.log"
}

usage() {
  sed -n '3,/^$/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
}

# Reads the tests of the suite file $1 into TESTS, as its "<name>|<class>|<args>" lines, exiting if there are none,
# or if any has no name or class.
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
      error "No name or class in line $number of $suite_file: $line"
      exit 1
    fi
    TESTS+=("$line")
  done < "$suite_file"
  if [ ${#TESTS[@]} -eq 0 ]; then
    error "No tests in $suite_file"
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

# Selects the env $1, if given, for every afon.sh command, exiting if it has no config dir.
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

# Makes the env $1's AWS full stack cluster the current kubectl context, exiting if it fails.
update_kube_config() {
  banner "Updating the kubectl context to the AWS full stack cluster of env $1"
  if ! ./afon.sh -a aws-update-kube-config full -e "$1"; then
    error "Failed to update the kubectl context for env $1: not running the tests"
    exit 1
  fi
}

# Prints property $1 of afon-aws.properties, without whitespace, or nothing if it is not set.
aws_property() {
  grep -h "^$1=" "${AFON_HOME:-$HOME/.afon}/config/afon-aws.properties" 2>/dev/null | tail -1 | cut -d= -f2- \
    | tr -d '[:space:]' || true
}

# Prints the public IP of the NAT gateway of the cluster of the current kubectl context, the address all its
# outbound traffic comes from, or nothing if it cannot be found, eg if the context is not an EKS cluster's.
cluster_egress_ip() {
  local context region cluster ip
  context=$(kubectl config current-context 2>/dev/null || true)
  # As written by 'aws eks update-kubeconfig': arn:aws:eks:<region>:<account>:cluster/<cluster>
  case "$context" in
    arn:aws:eks:*:cluster/*) ;;
    *) return 0 ;;
  esac
  region=$(echo "$context" | cut -d: -f4)
  cluster=$(echo "$context" | cut -d/ -f2)
  # The NAT gateway is named after the cluster, see AwsComponentNames.getNatGatewayName.
  ip=$(aws ec2 describe-nat-gateways --region "$region" \
         --filter "Name=tag:Name,Values=cdktf-eks-nat-$cluster" Name=state,Values=available \
         --query 'NatGateways[0].NatGatewayAddresses[0].PublicIp' --output text 2>/dev/null || true)
  if [ -n "$ip" ] && [ "$ip" != "None" ]; then
    echo "$ip"
  fi
}

# Restricts the Polaris ingress of the current kubectl context to the CIDRs AwsPolarisStack admits when
# AWS_POLARIS_ALLOWED_CIDRS is set: those CIDRs plus the cluster's egress IP, so that its pods calling Polaris by
# its public domain name are not locked out.  The egress IP is the one of the cluster's NAT gateway, as the
# AWS_EGRESS_IP of afon-aws.properties may be stale, eg if the stack was deployed without it, or by default
# AWS_EGRESS_IP if the NAT gateway cannot be found.  Exits if the annotation fails, and is skipped, with a
# warning, if there is no egress IP.
restrict_polaris_ingress() {
  local configured_ip egress_ip cidrs cidr
  configured_ip=$(aws_property AWS_EGRESS_IP)
  egress_ip=$(cluster_egress_ip)
  if [ -z "$egress_ip" ]; then
    warning "no NAT gateway found for kubectl context $(kubectl config current-context 2>/dev/null || true):" \
            "using AWS_EGRESS_IP '$configured_ip'"
    egress_ip="$configured_ip"
  elif [ -n "$configured_ip" ] && [ "$configured_ip" != "$egress_ip" ]; then
    warning "the cluster's egress IP $egress_ip is not AWS_EGRESS_IP $configured_ip: using $egress_ip." \
            "Update AWS_EGRESS_IP for the stack to keep its egress IP across rebuilds"
  fi
  if [ -z "$egress_ip" ]; then
    warning "no egress IP for the cluster: not restricting the Polaris ingress"
    return
  fi
  cidrs=
  for cidr in $(aws_property AWS_POLARIS_ALLOWED_CIDRS | tr ',' ' '); do
    if [ "$cidr" != "$egress_ip/32" ]; then
      cidrs="$cidrs$cidr,"
    fi
  done
  cidrs="$cidrs$egress_ip/32"
  banner "Restricting the Polaris ingress to $cidrs"
  if ! kubectl annotate ingress polaris-ingress -n default --overwrite \
         "alb.ingress.kubernetes.io/inbound-cidrs=$cidrs"; then
    error "Failed to restrict the Polaris ingress: not running the tests"
    exit 1
  fi
}

# Deploys the comma-separated stacks $1, in order, exiting if any fails.  Each stack is recorded in DEPLOYED_STACKS
# before being deployed, so that a stack whose deploy failed part way is also handled by finish_stacks, which is
# run on exit, however the script exits.
deploy_stacks() {
  local stack
  if [ -z "$1" ]; then
    return
  fi
  trap finish_stacks EXIT
  trap 'INTERRUPTED=true; exit 130' INT
  trap 'INTERRUPTED=true; exit 143' TERM
  for stack in ${1//,/ }; do
    DEPLOYED_STACKS+=("$stack")
    banner "Deploying stack $stack"
    if ! ./afon.sh -a manage-stack deploy "$stack"; then
      error "Failed to deploy stack $stack: not running the tests"
      exit 1
    fi
  done
}

# Prints the stacks of DEPLOYED_STACKS in reverse order, the order in which to tear them down.
reversed_deployed_stacks() {
  local index
  index=$((${#DEPLOYED_STACKS[@]} - 1))
  while [ $index -ge 0 ]; do
    echo "${DEPLOYED_STACKS[$index]}"
    index=$((index - 1))
  done
}

# Prints the commands deleting the stacks of DEPLOYED_STACKS, in the order in which to run them.
print_delete_commands() {
  local stack
  for stack in $(reversed_deployed_stacks); do
    echo "  ./afon.sh -a manage-stack delete $stack"
  done
}

# Tears down the stacks of DEPLOYED_STACKS, in reverse order, with './afon.sh -a manage-stack delete <stack>',
# trying all of them, and returning non-zero if any fails.
teardown_stacks() {
  local stack failed=0
  for stack in $(reversed_deployed_stacks); do
    banner "Tearing down stack $stack"
    if ! "$AFON_ROOT/afon.sh" -a manage-stack delete "$stack"; then
      warning "Failed to tear down stack $stack: delete it with './afon.sh -a manage-stack delete $stack'"
      failed=1
    fi
  done
  return $failed
}

# The EXIT trap of deploy_stacks, keeping the exit status, unless a teardown fails.  Never tears down the deployed
# stacks if the script was interrupted, as a deploy may then have been stopped part way, with its tofu state still
# locked, and a teardown would take as long as the deploy: it only says how to delete them.  Else it tears them
# down if --teardown is given, or says how to delete them.
finish_stacks() {
  local status=$?
  trap - EXIT
  if [ ${#DEPLOYED_STACKS[@]} -eq 0 ]; then
    exit $status
  fi
  if [ -n "$INTERRUPTED" ]; then
    error "Interrupted: not tearing down the deployed stacks, which may be part way deployed.  Delete them with:"
    print_delete_commands >&2
  elif [ -n "$TEARDOWN" ]; then
    if ! teardown_stacks; then
      status=1
    fi
  else
    echo "Leaving the deployed stacks up, as --teardown is not given.  Delete them with:"
    print_delete_commands
  fi
  exit $status
}

DEPLOY=
DEPLOYED_STACKS=()
INTERRUPTED=
TEARDOWN=
ENV=
LIST=
LOGGING_CONSOLE=
SELECTED=
SUITE_FILE="$DEFAULT_SUITE_FILE"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env|-e)
      require_value "$1" "${2:-}"; ENV="$2"; shift 2 ;;
    --deploy|-d)
      require_value "$1" "${2:-}"; DEPLOY="$2"; shift 2 ;;
    --teardown)
      TEARDOWN=true; shift ;;
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
    *)
      error "Unknown option: $1"
      usage >&2; exit 1 ;;
  esac
done

if [ -n "$TEARDOWN" ] && [ -z "$DEPLOY" ]; then
  error "Option --teardown requires --deploy"
  usage >&2; exit 1
fi

load_tests "$SUITE_FILE"
if [ -n "$LIST" ]; then
  list_tests; exit 0
fi

validate_tests "$SELECTED"

use_env "$ENV"

if [ -n "$LOGGING_CONSOLE" ]; then
  export AFON_LOGGING_CONSOLE="$LOGGING_CONSOLE"
fi

cd "$AFON_ROOT"

deploy_stacks "$DEPLOY"

if [ -n "$ENV" ]; then
  update_kube_config "$ENV"
  restrict_polaris_ingress
fi

PASSED=()
FAILED=()
for test_line in "${TESTS[@]}"; do
  name=$(test_field "$test_line" 1)
  class=$(test_field "$test_line" 2)
  args=$(test_field "$test_line" 3)
  is_selected "$name" || continue
  banner "Running test $name: $class $args"
  exec_args=(-a exec -ec "$class")
  if [ -n "$args" ]; then
    exec_args+=(-ea "$args")
  fi
  if MAVEN_OPTS="${MAVEN_OPTS:-} $QUIET_MAVEN_OPTS" ./afon.sh --mvn-quiet "${exec_args[@]}"; then
    PASSED+=("$name")
    success "Test $name passed"
  else
    FAILED+=("$name")
    error "Test $name failed: see $(afon_log_file)"
  fi
done

echo
banner "Regression summary (log in $(afon_log_file))"
# Empty arrays are guarded, as expanding them fails under set -u in bash 3.2, the macOS default.
if [ ${#PASSED[@]} -gt 0 ]; then
  for name in "${PASSED[@]}"; do
    echo "${SUCCESS_COLOR}PASSED  $name${RESET_COLOR}"
  done
fi
if [ ${#FAILED[@]} -gt 0 ]; then
  for name in "${FAILED[@]}"; do
    echo "${ERROR_COLOR}FAILED  $name${RESET_COLOR}"
  done
fi
echo "${#PASSED[@]} passed, ${#FAILED[@]} failed"
[ ${#FAILED[@]} -eq 0 ]
