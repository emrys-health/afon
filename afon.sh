#!/usr/bin/env bash
set -euo pipefail

AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS_JAVA_DIR="$AFON_ROOT/afon-ops/src/main/java/health/emrys/afon/ops"

# Lists the kebab-case names of the ops *Main classes (eg DeployAwsFullStackMain -> deploy-aws-full-stack).
# The ops/scripts package is excluded: it holds the script-command template, handled by the <dir>-<name> actions.
ops_command_names(){
  find "$OPS_JAVA_DIR" -name '*Main.java' -not -path '*/ops/scripts/*' 2>/dev/null \
    | sed -E 's|.*/||; s|Main\.java$||; s|([a-z0-9])([A-Z])|\1-\2|g' \
    | tr 'A-Z' 'a-z' | sort
}

usage (){
  echo "Usage: $0 [--action|-a <action>] [--help|-h]"
  echo "          [--home <afon-home>] [--cdktf <afon-cdktf>] [--env|-e <afon-env>]"
  echo "          [--cdktf-env <cdktf-env>]"
  echo "          [--config-env <config-env>] [--config-zip <zip>]"
  echo "          [--cloud|-c <cloud>]"
  echo "          [--logging|-l <logging>] [--logging-console|-lc <console>] [--mvn-quiet]"
  echo "          [--ansible-playbook|-ap <playbook>] [--ansible-args|-aa <args>]"
  echo "          [--build-args|-ba <args>] [--build-goals|-bg <goals>] [--build-profiles|-bp <profiles>]"
  echo "          [--exec-class|-ec <class>] [--exec-args|-ea <args>] [--exec-envs|-ee <envs>] [--exec-package|-ep <package>]"
  echo "          [--script-path|-sp <path>] [--script-args|-sa <args>]"
  echo "          [--setup-output <file>]"
  echo "actions:  ansible|build|exec|script|<dir>-<name>|<ops-command>"
  echo "          ansible:      : executes the ansible playbook."
  echo "          build         : executes the mvn build."
  echo "          exec          : executes the jvm classes from ops (see the docs for details)."
  echo "          script        : executes the scripts."
  echo "          <dir>-<name>  : executes scripts/<dir>/<name>.sh directly, for any script under scripts/."
  echo "                          eg cdktf-status, config-switch, aws-login, azure-account"
  echo "                          Its script args can be given as positional args instead of --script-args, eg"
  echo "                          '-a github-secrets push --env dev'. Use '--' before any other arg starting with '-'."
  echo "          <ops-command> : executes the ops class <OpsCommand>Main, eg deploy-aws-full-stack for"
  echo "                          'exec -ec ops.DeployAwsFullStackMain'.  The other --exec-* options still apply."
  echo "                          Its exec args can be given as positional args instead of --exec-args, eg"
  echo "                          '-a manage-polaris-catalogs create aws'. Use '--' before any other arg starting with '-'."
  echo "                          The ops commands are:"
  ops_command_names | sed 's/^/                          /'
  echo "help:             the help flag.  Prints usage."
  echo "home:             the afon home dir.  Defaults to '$HOME/.afon'."
  echo "                  Also settable via env variable 'AFON_HOME' (the flag wins over the env variable)."
  echo "cdktf:            the afon cdktf dir.  Defaults to 'AFON_HOME/cdktf'."
  echo "                  Also settable via env variable 'AFON_CDKTF' (the flag wins over the env variable)."
  echo "env:              the afon env,  Sets both cdktf-env and config-env."
  echo "                  Also settable via env variable 'AFON_ENV', which does not override the cdktf-env and config-env flags."
  echo "cdktf-env:        the cdktf env name.  Assumes cdktf dir 'AFON_HOME/cdktf-<env>' to symlink to 'AFON_HOME/cdktf'."
  echo "config-env:       the config env name.  Assumes config dir 'AFON_HOME/config-<env>' to symlink to 'AFON_HOME/config'."
  echo "config-zip:       the config zip path to import/export as a zip file."
  echo "cloud:            the cloud to target. One of aws|azure|gcp. If not specified all are targeted."
  echo "logging:          the logging level.  One of DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "                  Also settable via env variable 'AFON_LOGGING' (the flag wins over the env variable)."
  echo "logging-console:  the logging console level.  One of OFF|DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "                  Also settable via env variable 'AFON_LOGGING_CONSOLE' (the flag wins over the env variable)."
  echo "mvn-quiet:        the flag to hush the mvn output"
  echo "ansible-playbook: the ansible args.  Defaults to 'runner-setup.yml'."
  echo "ansible-args:     the ansible args.  eg '--tags verify'."
  echo "build-args:       the build args.  eg '--offline'."
  echo "build-goals:      the build goals.  Defaults to 'clean install'."
  echo "build-profiles:   the build profiles.  One of acceptance|cli|distribution.  Defaults to 'acceptance'."
  echo "exec-class:       the jvm class to execute.  Can be absolute or relative to the exec package."
  echo "exec-args:        the jvm exec args (must be quoted if more than one arg)"
  echo "exec-envs:        the jvm exec envs as key=value pairs (must be quoted if more than one pair)"
  echo "exec-package:     the jvm base package of the class to execute.  Defaults to 'health.emrys.afon'"
  echo "script-path:      the path of the script the run."
  echo "script-args:      the script args (must be quoted if more than one arg).  Also used for tofu-* actions."
  echo "setup-output:     the output file where to write the setup.  Defaults to 'versions.md'"
}

check_acceptance(){
  SCENARIOS_FILE="afon-acceptance/target/surefire-reports/scenarios.txt"
  if [ -f "$SCENARIOS_FILE" ]; then
    echo "Checking all acceptance specs have been run in $SCENARIOS_FILE"
    for SPEC in $(find afon-acceptance -name \*Spec.scala 2>/dev/null | perl -pe s/.*\\///g | perl -pe s/.scala//g | grep -v Abstract) ; do {
      grep $SPEC $SCENARIOS_FILE >/dev/null || echo "$SPEC has not been run"
    } done
  fi
}

exec_ansible(){
  cd $AFON_ROOT/scripts/ansible
  if [ -z "$ANSIBLE_PLAYBOOK" ]; then
    ANSIBLE_PLAYBOOK="runner-setup.yml"
  fi
  echo "Executing: ansible-playbook $ANSIBLE_PLAYBOOK $ANSIBLE_ARGS"
  ansible-playbook $ANSIBLE_PLAYBOOK $ANSIBLE_ARGS
}

exec_cdktf_switch(){
  SCRIPT_PATH="cdktf/switch.sh"
  exec_script
  CDKTF_SWITCHED=true
}

exec_config_switch(){
  SCRIPT_PATH="config/switch.sh"
  exec_script
  CONFIG_SWITCHED=true
}

find_script_path(){
  # $1 is a "<dir>-<name>" action, eg "aws-create" or "cdktf-status".  Sets SCRIPT_PATH and returns 0
  # if the script exists, else returns 1.  Scripts named with underscores (eg
  # aws/create_federated_permissions.sh) are still found: hyphens in the action are tried first
  # as-is, then converted to underscores.
  DIR="${1%%-*}"
  NAME="${1#*-}"
  if [ -f "$AFON_ROOT/scripts/$DIR/$NAME.sh" ]; then
    SCRIPT_PATH="$DIR/$NAME.sh"
  elif [ -f "$AFON_ROOT/scripts/$DIR/${NAME//-/_}.sh" ]; then
    SCRIPT_PATH="$DIR/${NAME//-/_}.sh"
  else
    return 1
  fi
}

resolve_script_path(){
  if ! find_script_path "$1"; then
    echo "Action not supported: $1 (no script scripts/$DIR/$NAME.sh)"
    usage; exit 0
  fi
}

exec_action_script(){
  if [ "$ACTION" == "config-switch" ]; then
    # Already run if --config-env triggered the global switch above.
    [ -n "$CONFIG_SWITCHED" ] || exec_config_switch
    return
  fi
  if [ "$ACTION" == "cdktf-switch" ]; then
    # Already run if --cdktf-env triggered the global switch above.
    [ -n "$CDKTF_SWITCHED" ] || exec_cdktf_switch
    return
  fi
  resolve_script_path "$ACTION"
  exec_script
}

find_ops_class(){
  # Prints the ops class (relative to the base package, eg "ops.DeployAwsFullStackMain") for the
  # "<kebab-name>" action $1, eg "deploy-aws-full-stack", or returns 1 if there is none.
  # Pure bash: the class is looked up in the source tree, no dependency on the cli.
  # Meant to be called in a subshell, as find_script_path sets globals.
  [[ "$1" == *-* ]] || return 1
  # A script scripts/<dir>/<name>.sh for the same action wins over an ops class.
  if find_script_path "$1"; then
    return 1
  fi
  local pascal="" part parts
  IFS=- read -ra parts <<< "$1"
  for part in "${parts[@]}"; do
    pascal="$pascal$(capitaliseFirst "$part")"
  done
  local file
  file=$(find "$OPS_JAVA_DIR" -name "${pascal}Main.java" -not -path '*/ops/scripts/*' 2>/dev/null | head -1)
  [ -n "$file" ] || return 1
  file="${file#"$OPS_JAVA_DIR"/}"
  echo "ops.$(echo "${file%.java}" | tr / .)"
}

resolve_ops_action(){
  # Rewrites an ops command action, eg "deploy-aws-full-stack", into the equivalent of
  # "exec -ec ops.DeployAwsFullStackMain".
  local ops_class
  ops_class=$(find_ops_class "$ACTION") || return 0
  EXEC_CLASS="$ops_class"
  echo "Action $ACTION is the ops command $EXEC_CLASS"
  ACTION="exec"
}

mvn_envs(){
  MVN_ENVS="-Dafon.home=$HOME_DIR -Dafon.cdktf=$CDKTF_DIR"
  if [ -n "$CLOUD" ]; then
    MVN_ENVS="$MVN_ENVS -Dafon.cloud=$CLOUD"
  fi
  if [ -n "$LOGGING" ]; then
    MVN_ENVS="$MVN_ENVS -Dafon.logging=$LOGGING"
  fi
  if [ -n "$LOGGING_CONSOLE" ]; then
    MVN_ENVS="$MVN_ENVS -Dafon.logging.console=$LOGGING_CONSOLE"
  fi
}

exec_mvn() {
  MVN="$MVN_CMD $MVN_PROFILES $MVN_ENVS $MVN_ARGS $MVN_GOALS"
  echo "Executing: $MVN"
  eval "$MVN"
}

exec_jvm(){
  if [ -z "$EXEC_PACKAGE" ]; then
    EXEC_PACKAGE="health.emrys.afon"
  fi
  if [ -z "$EXEC_CLASS" ]; then
    echo "Action exec requires option --exec-class"
    usage; exit 0
  fi
  if [ -n "$EXEC_PACKAGE" ]; then
    if [[ "$EXEC_CLASS" != "$EXEC_PACKAGE"* ]]; then
      EXEC_CLASS="$EXEC_PACKAGE.$EXEC_CLASS"
    fi
  fi
  MVN_GOALS="-Pexec exec:exec"
  MVN_ARGS="-Dexec.mainClass=$EXEC_CLASS"
  if [ -n "$EXEC_ARGS" ]; then
    MVN_ARGS="$MVN_ARGS -Dexec.args=\"$EXEC_ARGS\""
  fi
  if [ -n "$EXEC_ENVS" ]; then
    EXEC_JVM_ENVS=
    for pair in $EXEC_ENVS; do
      EXEC_JVM_ENVS="$EXEC_JVM_ENVS -D$pair"
    done
    EXEC_JVM_ENVS="${EXEC_JVM_ENVS# }"
    MVN_ARGS="$MVN_ARGS -Dexec.envs=\"$EXEC_JVM_ENVS\""
  fi
  mvn_envs
  cd $AFON_ROOT/afon-ops
  exec_mvn
}

exec_script(){
  if [ -z "$SCRIPT_PATH" ]; then
    echo "Action script requires option --script-path"
    usage; exit 0
  fi
  echo "Executing $SCRIPT_PATH $SCRIPT_ARGS"
  source $AFON_ROOT/scripts/$SCRIPT_PATH $SCRIPT_ARGS
}

capitaliseFirst(){
  echo "$1" | awk '{print toupper(substr($0,1,1)) substr($0,2)}'
}

version(){
  mvn help:evaluate -Dexpression=project.version -q -DforceStdout
}

ACTION=
ANSIBLE_PLAYBOOK=
ANSIBLE_ARGS=
BUILD_ARGS=
BUILD_GOALS=
BUILD_PROFILES=
CDKTF_DIR=
CDKTF_ENV=
CDKTF_SWITCHED=
CLOUD=
CONFIG_ENV=
CONFIG_SWITCHED=
CONFIG_ZIP=
ENV=
EXEC_CLASS=
EXEC_ARGS=
EXEC_ENVS=
EXEC_PACKAGE=
HELP=
HOME_DIR=
LOGGING=
LOGGING_CONSOLE=
MVN_PROFILES=
MVN_GOALS=
MVN_ARGS=
MVN_ENVS=
MVN_QUIET=
SCRIPT_PATH=
SCRIPT_ARGS=
SETUP_OUTPUT=
POSITIONAL_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --action|-a)
      ACTION="$2"; shift 2 ;;
    --ansible-playbook|-ap)
      ANSIBLE_PLAYBOOK="$2"; shift 2 ;;
    --ansible-args|-aa)
      ANSIBLE_ARGS="$2"; shift 2 ;;
    --build-args|-ba)
      BUILD_ARGS="$2"; shift 2 ;;
    --build-goals|-bg)
      BUILD_GOALS="$2"; shift 2 ;;
    --build-profiles|-bp|-p)
      BUILD_PROFILES="$2"; shift 2 ;;
    --cdktf)
      CDKTF_DIR="$2"; shift 2 ;;
    --cdktf-env)
      CDKTF_ENV="$2"; shift 2 ;;
    --cloud|-c)
      CLOUD="$2"; shift 2 ;;
    --config-env)
      CONFIG_ENV="$2"; shift 2 ;;
    --config-zip)
      CONFIG_ZIP="$2"; shift 2 ;;
    --env|-e)
      ENV="$2"; shift 2 ;;
    --exec-class|-ec)
      EXEC_CLASS="$2"; shift 2 ;;
    --exec-args|-ea)
      EXEC_ARGS="$2"; shift 2 ;;
    --exec-envs|-ee)
      EXEC_ENVS="$2"; shift 2 ;;
    --exec-package|-ep)
      EXEC_PACKAGE="$2"; shift 2 ;;
    --home)
      HOME_DIR="$2"; shift 2 ;;
    --help|-h)
      HELP=true; shift ;;
    --logging|-l)
      LOGGING="$2"; shift 2 ;;
    --logging-console|-lc)
      LOGGING_CONSOLE="$2"; shift 2 ;;
    --mvn-quiet)
      MVN_QUIET=true; shift ;;
    --script-path|-sp)
      SCRIPT_PATH="$2"; shift 2 ;;
    --script-args|-sa)
      SCRIPT_ARGS="$2"; shift 2 ;;
    --setup-output)
      SETUP_OUTPUT="$2"; shift 2 ;;
    --)
      shift; POSITIONAL_ARGS+=("$@"); break ;;
    -*)
      echo "Unknown option: $1" >&2;
      usage; exit 0
      ;;
    *)
      POSITIONAL_ARGS+=("$1"); shift ;;
  esac
done

# Positional args are only accepted by the ops commands and the <dir>-<name> script actions, where they are
# the exec args or the script args, ie "-a manage-polaris-catalogs create aws" is the same as
# "-a manage-polaris-catalogs -ea 'create aws'", and "-a github-secrets -- push --env dev" is the same as
# "-a github-secrets -sa 'push --env dev'".  find_ops_class and find_script_path are mutually exclusive
# (the former defers to the latter), so ACTION resolves to at most one of them.
if [ ${#POSITIONAL_ARGS[@]} -gt 0 ]; then
  if [ -n "$(find_ops_class "$ACTION")" ]; then
    if [ -n "$EXEC_ARGS" ]; then
      echo "The ops command $ACTION takes its args either as positional args or via --exec-args, not both." >&2
      usage; exit 0
    fi
    EXEC_ARGS="${POSITIONAL_ARGS[*]}"
  elif find_script_path "$ACTION" 2>/dev/null; then
    if [ -n "$SCRIPT_ARGS" ]; then
      echo "The script $ACTION takes its args either as positional args or via --script-args, not both." >&2
      usage; exit 0
    fi
    SCRIPT_ARGS="${POSITIONAL_ARGS[*]}"
  else
    echo "Unknown option: ${POSITIONAL_ARGS[0]}" >&2
    usage; exit 0
  fi
fi

echo "Using AFON_ROOT=$AFON_ROOT"

# A flag always wins over its env var, as in afon-cli.sh: the env var only applies when the flag is not given.
AFON_HOME="${AFON_HOME:-}"
if [ -z "$HOME_DIR" ] && [ -n "$AFON_HOME" ]; then
  HOME_DIR="$AFON_HOME"
fi

if [ -n "$HOME_DIR" ]; then
  echo "Using home dir $HOME_DIR"
else
  HOME_DIR="$HOME/.afon"
  echo "Home dir not specified. Defaulting to $HOME_DIR"
fi
# The scripts read AFON_HOME, so it must be the dir resolved from the flag, env var or default.
AFON_HOME="$HOME_DIR"
echo "Setting AFON_HOME=$AFON_HOME"

AFON_ENV="${AFON_ENV:-}"
if [ -n "$ENV" ]; then
  # The --env flag sets both envs, overriding the --cdktf-env and --config-env flags.
  echo "Using env=$ENV"
  echo "Both cdktf and config env are set to $ENV"
  CDKTF_ENV="$ENV"
  CONFIG_ENV="$ENV"
elif [ -n "$AFON_ENV" ]; then
  # The AFON_ENV env var only sets the envs not given by the --cdktf-env and --config-env flags.
  ENV="$AFON_ENV"
  echo "Using env=$ENV, from AFON_ENV"
  CDKTF_ENV="${CDKTF_ENV:-$ENV}"
  CONFIG_ENV="${CONFIG_ENV:-$ENV}"
  echo "Cdktf env is $CDKTF_ENV and config env is $CONFIG_ENV"
fi

CONFIG_DIR="$HOME_DIR/config"
echo "Using config dir $CONFIG_DIR"

if [ -n "$CONFIG_ENV" ]; then
  exec_config_switch
fi

AFON_CDKTF="${AFON_CDKTF:-}"
if [ -z "$CDKTF_DIR" ] && [ -n "$AFON_CDKTF" ]; then
  CDKTF_DIR="$AFON_CDKTF"
fi

if [ -n "$CDKTF_DIR" ]; then
  echo "Using cdktf dir $CDKTF_DIR"
else
  CDKTF_DIR="$HOME_DIR/cdktf"
  echo "Cdktf dir not specified. Defaulting to $CDKTF_DIR"
fi
AFON_CDKTF="$CDKTF_DIR"
echo "Setting AFON_CDKTF=$AFON_CDKTF"

if [ -n "$CDKTF_ENV" ]; then
  exec_cdktf_switch
fi

AFON_LOGGING="${AFON_LOGGING:-}"
if [ -z "$LOGGING" ] && [ -n "$AFON_LOGGING" ]; then
  LOGGING="$AFON_LOGGING"
fi

if [ -n "$LOGGING" ]; then
  echo "Using logging level: $LOGGING"
fi

AFON_LOGGING_CONSOLE="${AFON_LOGGING_CONSOLE:-}"
if [ -z "$LOGGING_CONSOLE" ] && [ -n "$AFON_LOGGING_CONSOLE" ]; then
  LOGGING_CONSOLE="$AFON_LOGGING_CONSOLE"
fi

if [ -n "$LOGGING_CONSOLE" ]; then
  echo "Using logging console level: $LOGGING_CONSOLE"
fi

if [ "$HELP" == "true" ]; then
    usage; exit 0;
fi

if [ "$ACTION" == "" ]; then
  ACTION="build"
  echo "Action not specified. Defaulting to $ACTION"
fi

if [ -n "$BUILD_PROFILES" ]; then
  echo "Using build profiles $BUILD_PROFILES"
  MVN_PROFILES="-P$BUILD_PROFILES"
fi

MVN_CMD="mvn"
if [ -n "$MVN_QUIET" ]; then
  MVN_CMD="$MVN_CMD -q"
fi
MVN_CMD="$MVN_CMD -s $AFON_ROOT/settings.xml"
resolve_ops_action
case $ACTION in
  ansible)
    exec_ansible
    ;;
  build)
    if [ -n "$BUILD_GOALS" ]; then
      MVN_GOALS="$BUILD_GOALS"
    else
      MVN_GOALS="clean install"
    fi
    if [ -n "$BUILD_ARGS" ]; then
      MVN_ARGS="$BUILD_ARGS"
    fi
    if [ -n "$CLOUD" ]; then
      echo "Using cloud '$CLOUD'"
        if [ -n "$MVN_PROFILES" ]; then
          MVN_PROFILES="$MVN_PROFILES,$CLOUD"
        else
          MVN_PROFILES="-P$CLOUD"
        fi
    fi
    mvn_envs
    exec_mvn
    check_acceptance
    ;;
  exec)
    exec_jvm
    ;;
  script)
    exec_script
    ;;
  *-*)
    exec_action_script
    ;;
  *)
    echo "Unknown action: $ACTION"
    ;;
esac
cd $AFON_ROOT

