#!/usr/bin/env bash
set -euo pipefail

usage (){
  echo "Usage: $0 [--action|-a <action>] [--cloud|-c <cloud>] [--dry-run] [--mvn-quiet] [--help|-h] [--home <afon-home>]"
  echo "          [--logging|-l <logging>] [--logging-console <console>]"
  echo "          [--ansible-playbook <playbook>] [--ansible-args <args>]"
  echo "          [--build-args <args>] [--build-goals|-g <goals>] [--build-profiles|-p <profiles>]"
  echo "          [--cli-command|-cli-cmd <command>] [--cli-args <args>] [--cli-dir <dir>]"
  echo "          [--config-zip <zip>] [--config-env <env>]"
  echo "          [--docs-dir|-d <dir>]"
  echo "          [--exec-package <package>] [--exec-class <class>] [--exec-args <args>] [--exec-envs <envs>] [--exec-type|-t <types>]"
  echo "          [--exec-script <script-name>]"
  echo "          [--setup-os <os>] [--setup-output <file>] [--setup-show]"
  echo "          [--stack-deploy] [--stack-type <type>] [--stack-args <args>] [--stack-status]"
  echo "          [--tofu-apply] [--tofu-destroy] [--tofu-state] [--tofu-args <args>] [--tofu-cloud <cloud>]"
  echo "actions:  ansible|build|config-*|deploy|docs|exec|setup|tofu-*"
  echo "          ansible:    : executes the ansible playbook."
  echo "          build       : executes the mvn build."
  echo "          cli         : executes the cli commands."
  echo "          config-*    : executes the config commands.  One of import|export|init|validate|switch|status."
  echo "          stack-*     : executes the stack commands, based on the <cloud> option and the <stack-type>. One of deploy|delete|status."
  echo "          docs-export : export the docs (both generated and static) to <docs-dir>."
  echo "          exec        : executes the jvm classes from ops (see the docs for details)."
  echo "          setup       : sets up the local env for the given os."
  echo "          tofu-*      : executes the tofu commands.  One of apply|destroy|state."
  echo "cloud:            the cloud to target. One of aws|azure|gcp. If not specified all are targetted."
  echo "logging:          the logging level.  One of DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "logging-console:  the logging console level.  One of OFF|DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "ansible-playbook: the ansible args.  Defaults to 'runner-setup.yml'."
  echo "ansible-args:     the ansible args.  eg '--tags verify'."
  echo "build-args:       the build args.  eg '-DskipTests'."
  echo "build-goals:      the build goals.  Defaults to 'clean install'."
  echo "build-profiles:   the build profiles.  One of acceptance|ops|samples.  If not specified all are active."
  echo "cli-command:      the cli command."
  echo "cli-args:         the cli args."
  echo "cli-dir:          the cli dir.  Defaulting to env `$AFON_CLI_DIR` if set, else to `$AFON_HOME/cli`."
  echo "config-zip:       the config zip path to import/export as a zip file."
  echo "config-env:       the config env name.  Assumes config dir '$AFON_HOME/config-<env>' to symlink to '$AFON_HOME/config'."
  echo "docs-dir:         the output dir for the docs. Defaulting to '$AFON_HOME/docs'."
  echo "exec-types:       the exec types.  One of ops|samples|script.  Defaults to 'ops'."
  echo "exec-package:     the jvm base package of the class to execute.  Defaults to 'health.emrys.afon'"
  echo "exec-class:       the jvm class to execute.  Can be absolute or relative to the exec package.  Required for exec types 'ops' and 'samples'."
  echo "exec-args:        the jvm exec args (must be quoted if more than one arg)"
  echo "exec-envs:        the jvm exec envs as key=value pairs (must be quoted if more than one pair)"
  echo "setup-os:         the os to setup.  One of linux|macos.  Defaults to '$(uname -a|awk '{print $NF}')'"
  echo "setup-output:     the output file where to write the setup.  Defaults to 'versions.md'"
  echo "setup-show:       the flag to show the current setup, without executing the setup commands."
  echo "stack-type:       the stack type.  One of full|polaris.  Uses specified or default <cloud>. Defaults to 'full'."
  echo "stack-args:       the stack command args."
  echo "tofu-args:        the tofu args."
  echo "tofu-cloud:       the cloud used by tofu. One of aws|azure|gcp. Defaults to 'aws'."
  echo "mvn-quiet:        the flag to hush the mvn output"
  echo "help:             the help flag.  Prints usage."
  echo "home:             the afon home dir.  Defaults to $HOME/.afon."
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
  cd $CWD/scripts/ansible
  if [ -z "$ANSIBLE_PLAYBOOK" ]; then
    ANSIBLE_PLAYBOOK="runner-setup.yml"
  fi
  echo "Executing: ansible-playbook $ANSIBLE_PLAYBOOK $ANSIBLE_ARGS"
  ansible-playbook $ANSIBLE_PLAYBOOK $ANSIBLE_ARGS
}

exec_cli(){
  if [ -z "$CLI_DIR" ]; then
    AFON_CLI_DIR=${AFON_CLI_DIR:-""}
    if [ -n "$AFON_CLI_DIR" ]; then
      echo "Using cli dir $AFON_CLI_DIR"
      CLI_DIR="$AFON_CLI_DIR"
    else
      echo "Using cli dir $AFON_HOME/cli"
      CLI_DIR="$AFON_HOME/cli"
    fi
  fi
  if [ ! -d "$CLI_DIR" ]; then
    echo "The cli dir is not found: $CLI_DIR"
    usage; exit;
  fi
  CLI_JAR="$CLI_DIR/afon-cli.jar"
  CLI_SH="$CLI_DIR/afon-cli.sh"
  if [ ! -f "$CLI_JAR" ] || [ ! -f "$CLI_SH" ]; then
    echo "The cli artifacts are not found in $CLI_DIR"
    echo "Build cli artifacts:"
    echo "./afon.sh -p cli --build-args -Dafon.cli.dir=$CLI_DIR"
    usage; exit;
  fi
  CLI="$CLI_SH $CLI_COMMAND $CLI_ARGS"
  echo "Executing: $CLI"
  eval "$CLI"
}

exec_config(){
  CONFIG_DIR="$AFON_HOME/config"
  echo "Using config dir $CONFIG_DIR"
  CONFIG_ACTION=${ACTION#config-}
  case $CONFIG_ACTION in
    import)
        if [ -z "$CONFIG_ZIP" ]; then
          echo "Action $ACTION requires option --config-zip"
          usage; exit 1
        fi
        if [ -d $CONFIG_DIR ]; then
          echo "$CONFIG_DIR already exists.  Remove and retry."
          exit;
        fi
        if [ -f $CONFIG_ZIP ]; then
          mkdir -p $CONFIG_DIR
          unzip -jqu $CONFIG_ZIP -d $CONFIG_DIR
          echo "Imported $CONFIG_ZIP to $CONFIG_DIR."
        else
          echo "File $CONFIG_ZIP not found."
        fi
      ;;
    export)
      if [ -z "$CONFIG_ZIP" ]; then
        echo "Action $ACTION requires option --config-zip"
        usage; exit 1
      fi
      if [ -d $CONFIG_DIR ]; then
        zip -jr $CONFIG_ZIP $CONFIG_DIR
        echo "Exported $CONFIG_DIR to $CONFIG_ZIP."
      else
        echo "Dir $CONFIG_DIR not found."
      fi
    ;;
    init)
      if [ -d $CONFIG_DIR ]; then
        echo "$CONFIG_DIR already exists.  Remove and retry."
        exit;
      fi
      EXEC_TYPE="script"
      EXEC_SCRIPT="config/$CONFIG_ACTION.sh"
      exec_script
    ;;
    validate)
      EXEC_TYPE="script"
      EXEC_SCRIPT="config/$CONFIG_ACTION.sh"
      exec_script
    ;;
    switch)
      if [ -z "$CONFIG_ENV" ]; then
        echo "Action $ACTION requires option --config-env"
        usage; exit 1
      fi
      if [ -L "$CONFIG_DIR" ]; then
        rm $CONFIG_DIR
      elif [ -d "$CONFIG_DIR" ]; then
        echo "$CONFIG_DIR is not a symlink.  Remove manually."
        exit;
      fi
      CONFIG_ENV_DIR="$CONFIG_DIR-$CONFIG_ENV"
      ln -s $CONFIG_ENV_DIR $CONFIG_DIR
    ;;
    status)
      if [ -d $CONFIG_DIR ]; then
        if [ -f "$CONFIG_DIR/afon.md" ]; then
          cat $CONFIG_DIR/afon.md
        fi
        ls -lt $CONFIG_DIR
        ls -lt $CONFIG_DIR/*
      else
        echo "$CONFIG_DIR not found."
      fi
      ;;
  esac
}

exec_docs(){
  mkdir -p $DOCS_DIR
  ARTIFACT="health.emrys:afon-docs:$VERSION:zip:docs"
  mvn -q dependency:copy -Dartifact=$ARTIFACT -DoutputDirectory=$AFON_HOME
  unzip -qu $AFON_HOME/afon-docs-$VERSION-docs.zip -d $DOCS_DIR
  echo "Exported docs to $DOCS_DIR"
}

mvn_envs(){
  MVN_ENVS="-Dafon.home=$AFON_HOME"
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
  MVN="$MVN_CMD $MVN_ENVS $MVN_ARGS $MVN_GOALS"
  echo "Executing: $MVN"
  eval "$MVN"
}

exec_jvm(){
  if [ -z "$EXEC_PACKAGE" ]; then
    EXEC_PACKAGE="health.emrys.afon"
  fi
  if [ -z "$EXEC_CLASS" ]; then
    echo "Action exec requires option --exec-class"
    usage; exit 1
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
  mvn_envs
  if [ -n "$EXEC_ENVS" ]; then
    EXEC_ENVS="${EXEC_ENVS/ / -D}"
    MVN_ENVS="$MVN_ENVS -D$EXEC_ENVS"
  fi
  if [ -z "$EXEC_TYPE" ]; then
    echo "Action exec requires option --exec-type"
    usage; exit 1
  fi
  cd $CWD/afon-$EXEC_TYPE
  exec_mvn
}

exec_script(){
  if [ -z "$EXEC_SCRIPT" ]; then
    echo "Action exec requires option --exec-script"
    usage; exit 1
  fi
  cd $CWD/scripts
  source $EXEC_SCRIPT
}

capitaliseFirst(){
  echo "$1" | awk '{print toupper(substr($0,1,1)) substr($0,2)}'
}

exec_stack(){
  EXEC_TYPE="ops"
  EXEC_ARGS="$STACK_ARGS"
  STACK_ACTION=${ACTION#stack-}
  case $STACK_ACTION in
    deploy|delete)
      if [ -z "$STACK_TYPE" ]; then
        STACK_TYPE="full"
        echo "Stack type not specified.  Defaulting to '$STACK_TYPE'."
      fi
      ACTION_CF=$(capitaliseFirst $STACK_ACTION)
      CLOUD_CF=$(capitaliseFirst $CLOUD)
      case $STACK_TYPE in
        full)
          EXEC_CLASS="ops.${ACTION_CF}${CLOUD_CF}FullStackMain"
          ;;
        polaris)
          EXEC_CLASS="ops.${ACTION_CF}${CLOUD_CF}PolarisMain"
          ;;
        *)
          echo "Stack type not supported: $STACK_TYPE"
          usage; exit 0
      esac
      echo "$ACTION_CF using $EXEC_CLASS"
      exec_jvm
      ;;
    status)
      EXEC_TYPE="script"
      case $CLOUD in
        aws)
          EXEC_SCRIPT="aws/vpcs.sh"
          ;;
        *)
          echo "Stack status for $CLOUD not yet supported"
          ;;
      esac
      exec_script
      ;;
    *)
      echo "Stack action not supported: $STACK_ACTION"
      usage; exit 0
  esac
}

exec_tofu(){
  echo "tofu targetting cloud '$TOFU_CLOUD'"
  TOFU_DIR="afon-ops/cdktf.out/stacks/$TOFU_CLOUD"
  if [ ! -d "$TOFU_DIR" ]; then
     echo "Dir $TOFU_DIR not found.  Execute deploy or configure actions first."
     usage; exit 0
  fi
  echo "Using tofu dir $TOFU_DIR"
  if [ -z "$TOFU_ACTION" ]; then
    # it may have already been set by caller
    TOFU_ACTION=${ACTION#tofu-}
  fi
  if [ "$TOFU_ACTION" != "state" ]; then
    TOFU_ARGS="$TOFU_ARGS -auto-approve"
  fi
  TOFU_CMD="tofu -chdir=$TOFU_DIR $TOFU_ACTION $TOFU_ARGS"
  echo "Executing: $TOFU_CMD"
  eval "$TOFU_CMD"
}

setup_env(){
  case $SETUP_OS in
    linux|GNU/Linux)
      sudo apt install git openjdk-17-jdk maven docker tofu kubectl awscli azure-cli google-cloud-cli
      # install eksctl hubble via tarball?
      ;;
    macos|arm64)
      brew install git openjdk@17 maven docker opentofu kubernetes-cli awscli azure-cli gcloud-cli
      brew install eksctl hubble
      ;;
    *)
      echo "Setup not supported for $SETUP_OS"
      ;;
  esac
}

setup_output(){
  echo "## Tools setup" > $SETUP_OUTPUT
  echo "### os" >> $SETUP_OUTPUT
  echo "$(uname -a)" >> $SETUP_OUTPUT
  echo "### kubectl" >> $SETUP_OUTPUT
  kubectl version >> $SETUP_OUTPUT
  echo "### tofu" >> $SETUP_OUTPUT
  tofu --version >> $SETUP_OUTPUT
  echo "### java" >> $SETUP_OUTPUT
  java --version >> $SETUP_OUTPUT
  echo "### mvn" >> $SETUP_OUTPUT
  mvn --version >> $SETUP_OUTPUT
  echo "### aws" >> $SETUP_OUTPUT
  aws --version >> $SETUP_OUTPUT
  echo "### az" >> $SETUP_OUTPUT
  az --version >> $SETUP_OUTPUT
  echo "### gcloud" >> $SETUP_OUTPUT
  gcloud --version >> $SETUP_OUTPUT
  echo "Setup output written to $SETUP_OUTPUT"
}

version(){
  mvn help:evaluate -Dexpression=project.version -q -DforceStdout
}

AFON_HOME=
ACTION=
ANSIBLE_PLAYBOOK=
ANSIBLE_ARGS=
BUILD_ARGS=
BUILD_GOALS=
BUILD_PROFILES=
CLI_ARGS=
CLI_COMMAND=
CLI_DIR=
CLOUD=
LOGGING=
LOGGING_CONSOLE=
DRY_RUN=false
CONFIG_ENV=
CONFIG_ZIP=
DOCS_DIR=
EXEC_CLASS=
EXEC_ARGS=
EXEC_ENVS=
EXEC_PACKAGE=
EXEC_SCRIPT=
EXEC_TYPE=
MVN_PROFILES=
MVN_GOALS=
MVN_ARGS=
MVN_ENVS=
MVN_QUIET=
SETUP_OS=
SETUP_OUTPUT=
SETUP_SHOW=
STACK_TYPE=
STACK_ARGS=
TOFU_ACTION=
TOFU_ARGS=
TOFU_CLOUD=
VERSION=

while [[ $# -gt 0 ]]; do
  case "$1" in
    --action|-a)
      ACTION="$2"; shift 2 ;;
    --ansible-playbook)
      ANSIBLE_PLAYBOOK="$2"; shift 2 ;;
    --ansible-args)
      ANSIBLE_ARGS="$2"; shift 2 ;;
    --build-args)
      BUILD_ARGS="$2"; shift 2 ;;
    --build-goals|-g)
      BUILD_GOALS="$2"; shift 2 ;;
    --build-profiles|-p)
      BUILD_PROFILES="$2"; shift 2 ;;
    --cloud|-c)
      CLOUD="$2"; shift 2 ;;
    --dry-run)
      DRY_RUN=true; shift ;;
    --cli-args)
      CLI_ARGS="$2"; shift 2 ;;
    --cli-command|--cli-cmd)
      CLI_COMMAND="$2"; shift 2 ;;
    --cli-dir)
      CLI_DIR="$2"; shift 2 ;;
    --config-env)
      CONFIG_ENV="$2"; shift 2 ;;
    --config-zip)
      CONFIG_ZIP="$2"; shift 2 ;;
    --docs-dir|-d)
      DOCS_DIR="$2"; shift 2 ;;
    --exec-class)
      EXEC_CLASS="$2"; shift 2 ;;
    --exec-args)
      EXEC_ARGS="$2"; shift 2 ;;
    --exec-envs)
      EXEC_ENVS="$2"; shift 2 ;;
    --exec-package)
      EXEC_PACKAGE="$2"; shift 2 ;;
    --exec-script)
      EXEC_SCRIPT="$2"; shift 2 ;;
    --exec-type|-t)
      EXEC_TYPE="$2"; shift 2 ;;
    --logging|-l)
      LOGGING="$2"; shift 2 ;;
    --logging-console)
      LOGGING_CONSOLE="$2"; shift 2 ;;
    --setup-os)
      SETUP_OS="$2"; shift 2 ;;
    --setup-outout)
      SETUP_OUTPUT="$2"; shift 2 ;;
    --setup-show)
      SETUP_SHOW=true; shift ;;
    --stack-type)
      STACK_TYPE="$2"; shift 2 ;;
    --stack-args)
      STACK_ARGS="$2"; shift 2 ;;
    --tofu-args)
      TOFU_ARGS="$2"; shift 2 ;;
    --tofu-cloud)
      TOFU_CLOUD="$2"; shift 2 ;;
    --mvn-quiet)
      MVN_QUIET=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    --home)
      AFON_HOME="$2"; shift 2 ;;
    *)
      echo "Unknown option: $1" >&2;
      usage; exit 1 ;;
  esac
done

if [ -z "$AFON_HOME" ]; then
  AFON_HOME="$HOME/.afon"
  echo "AFON_HOME not specified. Defaulting to $AFON_HOME"
else
  echo "Using AFON_HOME=$AFON_HOME"
fi

if [ "$ACTION" == "" ]; then
  ACTION="build"
  echo "Action not specified. Defaulting to $ACTION"
fi

if [ -n "$BUILD_PROFILES" ]; then
  echo "Using build profiles $BUILD_PROFILES"
  MVN_PROFILES="-P$BUILD_PROFILES"
fi

CWD=`pwd`
MVN_CMD="mvn"
if [ -n "$MVN_QUIET" ]; then
  MVN_CMD="$MVN_CMD -q"
fi
MVN_CMD="$MVN_CMD -s $CWD/settings.xml $MVN_PROFILES"
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
  cli)
    exec_cli
    ;;
  config-*)
    exec_config
    ;;
  docs-export)
    VERSION=$(version)
    if [ -z "$DOCS_DIR" ]; then
      DOCS_DIR="$AFON_HOME/docs"
      echo "Docs dir not specified.  Defaulting to '$DOCS_DIR'."
    fi
    exec_docs
    ;;
  exec)
    if [ -n "$EXEC_TYPE" ]; then
      echo "Using exec type '$EXEC_TYPE'"
    else
      echo "Exec type not specified.  Defaulting to 'ops'."
      EXEC_TYPE="ops"
    fi
    if [ "$EXEC_TYPE" == "script" ]; then
      exec_script
    else
      exec_jvm
    fi
    ;;
  setup)
    if [ -z "$SETUP_OS" ]; then
      SETUP_OS=$(uname -a| awk '{print $NF}')
    fi
    if [ -z "$SETUP_SHOW" ]; then
      # only perform setup if show flag is not set
      setup_env
    fi
    if [ -z "$SETUP_OUTPUT" ]; then
      SETUP_OUTPUT="versions.md"
    fi
    setup_output
    ;;
  stack-*)
    if [ -z "$CLOUD" ]; then
      CLOUD="aws"
      echo "Cloud not specified.  Defaulting to '$CLOUD'."
    fi
    exec_stack
    ;;
  tofu-*)
    if [ -z "$TOFU_CLOUD" ]; then
      TOFU_CLOUD="aws"
      echo "Defaulting to tofu cloud: $TOFU_CLOUD"
    fi
    if [ "$ACTION" == "tofu-destroy" ]; then
      # workaround until we sort out the issue of the hanging process
      # https://github.com/emrys-health/afon-core/issues/84
      TOFU_ACTION="state"
      TOFU_ARGS="rm helm_release.prometheusRelease"
      exec_tofu
      TOFU_ACTION="destroy"
      TOFU_ARGS=
      exec_tofu
    else
      exec_tofu
    fi
    ;;
  *)
    echo "Unknown action $ACTION"
    ;;
esac
cd $CWD

