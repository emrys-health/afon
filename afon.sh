#!/usr/bin/env bash
set -euo pipefail

usage (){
  echo "Usage: $0 [--action|-a <action>] [--help|-h]"
  echo "          [--home <afon-home>] [--cdktf <afon-cdktf>] [--env|-e <afon-env>]"
  echo "          [--cdktf-env <cdktf-env>] [--config-env <config-env>]"
  echo "          [--cloud|-c <cloud>]"
  echo "          [--logging|-l <logging>] [--logging-console|-lc <console>] [--mvn-quiet]"
  echo "          [--ansible-playbook|-ap <playbook>] [--ansible-args|-aa <args>]"
  echo "          [--build-args|-ba <args>] [--build-goals|-bg <goals>] [--build-profiles|-bp|-p <profiles>]"
  echo "          [--cli-command|-cc <command>] [--cli-args|-ca <args>] [--cli-dir|-cd <dir>]"
  echo "          [--config-zip <zip>]"
  echo "          [--docs-dir|-dd <dir>]"
  echo "          [--exec-class|-ec <class>] [--exec-args|-ea <args>] [--exec-envs|-ee <envs>] [--exec-package|-ep <package>] [--exec-type|-et <types>]"
  echo "          [--script-path|-sp <path>] [--script-args|-sa <args>]"
  echo "          [--setup-os <os>] [--setup-output <file>]"
  echo "          [--tofu-args|-ta <args>]"
  echo "actions:  ansible|build|config-*|deploy|docs|exec|setup|tofu-*"
  echo "          ansible:    : executes the ansible playbook."
  echo "          build       : executes the mvn build."
  echo "          cli         : executes the cli commands."
  echo "          cdktf-*     : executes the cdktf commands.  One of init|switch|status."
  echo "          config-*    : executes the config commands.  One of import|export|init|validate|switch|status."
  echo "          docs-export : export the docs (both generated and static) to <docs-dir>."
  echo "          exec        : executes the jvm classes from ops (see the docs for details)."
  echo "          script      : executes the scripts."
  echo "          setup-*     : sets up the local env for the given os and <cloud> option.  One of local|show"
  echo "          tofu-*      : executes the tofu commands.  One of apply|destroy|state."
  echo "help:             the help flag.  Prints usage."
  echo "home:             the afon home dir.  Defaults to '$HOME/.afon'."
  echo "                  Also settable via env variable 'AFON_HOME'."
  echo "cdktf:            the afon cdktf dir.  Defaults to 'AFON_HOME/cdktf'."
  echo "                  Also settable via env variable 'AFON_CDKTF'."
  echo "env:              the afon env,  Sets both cdktf-env and config-env."
  echo "                  Also settable via env variable 'AFON_ENV'."
  echo "cdktf-env:        the cdktf env name.  Assumes cdktf dir 'AFON_HOME/cdktf-<env>' to symlink to 'AFON_HOME/cdktf'."
  echo "config-env:       the config env name.  Assumes config dir 'AFON_HOME/config-<env>' to symlink to 'AFON_HOME/config'."
  echo "cloud:            the cloud to target. One of aws|azure|gcp. If not specified all are targeted."
  echo "logging:          the logging level.  One of DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "                  Also settable via env variable 'AFON_LOGGING'."
  echo "logging-console:  the logging console level.  One of OFF|DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "                  Also settable via env variable 'AFON_LOGGING_CONSOLE'."
  echo "mvn-quiet:        the flag to hush the mvn output"
  echo "ansible-playbook: the ansible args.  Defaults to 'runner-setup.yml'."
  echo "ansible-args:     the ansible args.  eg '--tags verify'."
  echo "build-args:       the build args.  eg '--offline'."
  echo "build-goals:      the build goals.  Defaults to 'clean install'."
  echo "build-profiles:   the build profiles.  One of acceptance|ops|samples.  If not specified all are active."
  echo "cli-command:      the cli command.  Exec <cli-dir>/afon-cli.sh for list of commands."
  echo "cli-args:         the cli args."
  echo "cli-dir:          the cli dir.  Defaulting to 'AFON_HOME/cli'."
  echo "                  Also settable via env variable 'AFON_CLI_DIR'."
  echo "config-zip:       the config zip path to import/export as a zip file."
  echo "docs-dir:         the output dir for the docs. Defaulting to 'AFON_HOME/docs'."
  echo "exec-class:       the jvm class to execute.  Can be absolute or relative to the exec package.  Required for exec types 'ops' and 'samples'."
  echo "exec-args:        the jvm exec args (must be quoted if more than one arg)"
  echo "exec-envs:        the jvm exec envs as key=value pairs (must be quoted if more than one pair)"
  echo "exec-package:     the jvm base package of the class to execute.  Defaults to 'health.emrys.afon'"
  echo "exec-type:        the exec type.  One of ops|samples.  Defaults to 'ops'."
  echo "script-path:      the path of the script the run."
  echo "script-args:      the script args (must be quoted if more than one arg)"
  echo "setup-os:         the os to setup.  One of linux|macos.  Defaults to '$(uname -a|awk '{print $NF}')'"
  echo "setup-output:     the output file where to write the setup.  Defaults to 'versions.md'"
  echo "tofu-args:        the tofu args."
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

exec_cli(){
  if [ ! -d "$CLI_DIR" ]; then
    echo "The cli dir is not found: $CLI_DIR"
    usage; exit 0
  fi
  CLI_JAR="$CLI_DIR/afon-cli.jar"
  CLI_SH="$CLI_DIR/afon-cli.sh"
  if [ ! -f "$CLI_JAR" ] || [ ! -f "$CLI_SH" ]; then
    echo "The cli artifacts are not found in $CLI_DIR"
    echo "Build cli artifacts:"
    echo "./afon.sh -p cli --build-args -Dafon.cli.dir=$CLI_DIR"
    usage; exit 0
  fi
  CLI="$CLI_SH $CLI_COMMAND $CLI_ARGS"
  echo "Executing: $CLI"
  eval "$CLI"
}

exec_cdktf(){
  CDKTF_ACTION=${ACTION#cdktf-}
  case $CDKTF_ACTION in
    init)
      exec_cdktf_init
      ;;
    switch)
      exec_cdktf_switch
      ;;
    status)
      exec_cdktf_status
      ;;
  esac
}

exec_cdktf_init(){
  INIT_DIR=
  if [ -n "$CDKTF_ENV" ]; then
    INIT_DIR="$CDKTF_DIR-$CDKTF_ENV"
  else
    INIT_DIR="$CDKTF_DIR_DIR"
  fi
  mkdir -p $INIT_DIR
}

exec_cdktf_switch(){
  if [ -z "$CDKTF_ENV" ]; then
    echo "Action cdktf-switch requires option --cdktf-env"
    usage; exit 0
  fi
  CDKTF_ENV_DIR="$CDKTF_DIR-$CDKTF_ENV"
  if [ -L "$CDKTF_DIR" ]; then
    rm $CDKTF_DIR
  elif [ -d "$CDKTF_DIR" ]; then
    echo "$CDKTF_DIR is not a symlink. Moving $CDKTF_DIR to $CDKTF_ENV_DIR"
    echo "NOTE:  You may have used option --env instead of narrower --cdktf-env."
    mv $CDKTF_DIR $CDKTF_ENV_DIR
  fi
  ln -s $CDKTF_ENV_DIR $CDKTF_DIR
  echo "Switched to cdktf env $CDKTF_ENV"
}

exec_cdktf_status(){
  if [ -d $CDKTF_DIR ]; then
    if [ -f "$CDKTF_DIR/manifest.json" ]; then
      cat $CDKTF_DIR/manifest.json
    fi
    ls -lt $CDKTF_DIR
    ls -lt $CDKTF_DIR/*
  else
    echo "$CDKTF_DIR not found."
  fi
}

exec_config(){
  CONFIG_ACTION=${ACTION#config-}
  case $CONFIG_ACTION in
    import)
        if [ -z "$CONFIG_ZIP" ]; then
          echo "Action $ACTION requires option --config-zip"
          usage; exit 0
        fi
        if [ -d $CONFIG_DIR ]; then
          echo "$CONFIG_DIR already exists.  Remove and retry."
          exit 0
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
        usage; exit 0
      fi
      if [ -d $CONFIG_DIR ]; then
        zip -jr $CONFIG_ZIP $CONFIG_DIR
        echo "Exported $CONFIG_DIR to $CONFIG_ZIP."
      else
        echo "Dir $CONFIG_DIR not found."
      fi
      ;;
    init|validate)
      SCRIPT_PATH="config/$CONFIG_ACTION.sh"
      exec_script
      ;;
    switch)
      exec_config_switch
      ;;
    status)
      exec_config_status
      ;;
  esac
}

exec_config_switch(){
  if [ -z "$CONFIG_ENV" ]; then
    echo "Action config-switch requires option --config-env"
    usage; exit 0
  fi
  CONFIG_ENV_DIR="$CONFIG_DIR-$CONFIG_ENV"
  if [ -L "$CONFIG_DIR" ]; then
    rm $CONFIG_DIR
  elif [ -d "$CONFIG_DIR" ]; then
    echo "$CONFIG_DIR is not a symlink. Moving $CONFIG_DIR to $CONFIG_ENV_DIR"
    echo "NOTE:  You may have used option --env instead of narrower --config-env."
    mv $CONFIG_DIR $CONFIG_ENV_DIR
  fi
  ln -s $CONFIG_ENV_DIR $CONFIG_DIR
  echo "Switched to config env $CONFIG_ENV"
}

exec_config_status(){
  if [ -d $CONFIG_DIR ]; then
    if [ -f "$CONFIG_DIR/afon.md" ]; then
      cat $CONFIG_DIR/afon.md
    fi
    ls -lt $CONFIG_DIR
    ls -lt $CONFIG_DIR/*
  else
    echo "$CONFIG_DIR not found."
  fi
}

exec_docs(){
  mkdir -p $DOCS_DIR
  ARTIFACT="health.emrys:afon-docs:$VERSION:zip:docs"
  mvn -q dependency:copy -Dartifact=$ARTIFACT -DoutputDirectory=$HOME_DIR
  unzip -qu $HOME_DIR/afon-docs-$VERSION-docs.zip -d $DOCS_DIR
  echo "Exported docs to $DOCS_DIR"
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
  if [ -z "$EXEC_TYPE" ]; then
    echo "Action exec requires option --exec-type"
    usage; exit 0
  fi
  cd $AFON_ROOT/afon-$EXEC_TYPE
  exec_mvn
}

exec_script(){
  if [ -z "$SCRIPT_PATH" ]; then
    echo "Action script requires option --script-path"
    usage; exit 0
  fi
  echo "Executing $SCRIPT_PATH $SCRIPT_ARGS"
  cd $AFON_ROOT/scripts
  source $SCRIPT_PATH $SCRIPT_ARGS
}

capitaliseFirst(){
  echo "$1" | awk '{print toupper(substr($0,1,1)) substr($0,2)}'
}

exec_tofu(){
  TOFU_CLOUD=
  if [ -n "$CLOUD" ]; then
    TOFU_CLOUD="$CLOUD"
  else
    TOFU_CLOUD="aws"
  fi
  TOFU_DIR="$CDKTF_DIR/stacks/$TOFU_CLOUD"
  echo "Using tofu dir $TOFU_DIR"
  if [ ! -d "$TOFU_DIR" ]; then
     echo "Dir $TOFU_DIR not found.  Execute deploy first."
     usage; exit 0
  fi
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

install_eksct_linux(){
  curl -sLO "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_Linux_amd64.tar.gz" && tar -xzf eksctl_Linux_amd64.tar.gz -C /tmp && sudo mv /tmp/eksctl /usr/local/bin && rm eksctl_Linux_amd64.tar.gz
}

install_kubelogin(){
  az aks install-cli
}

setup_local(){
  case $SETUP_OS in
    linux|GNU/Linux)
      sudo apt install git openjdk-17-jdk maven docker tofu kubectl hubble
      if [ -z "$CLOUD" ]; then
        sudo apt install awscli
        install_eksct_linux
        sudo apt install azure-cli
        sudo apt install google-cloud-cli
      fi
      if [ "$CLOUD" == "aws" ]; then
        sudo apt install awscli
        install_eksct_linux
      fi
      if [ "$CLOUD" == "azure" ]; then
        sudo apt install azure-cli
        install_kubelogin
      fi
      if [ "$CLOUD" == "gcp" ]; then
        sudo apt install google-cloud-cli
      fi
      ;;
    macos|arm64)
      brew install git openjdk@17 maven docker opentofu kubernetes-cli hubble
      if [ -z "$CLOUD" ]; then
        brew install awscli eksctl
        brew install azure-cli
        brew install gcloud-cli
      fi
      if [ "$CLOUD" == "aws" ]; then
        brew install awscli eksctl
      fi
      if [ "$CLOUD" == "azure" ]; then
        brew install azure-cli
        install_kubelogin
      fi
      if [ "$CLOUD" == "gcp" ]; then
        brew install gcloud-cli
      fi
      ;;
    *)
      echo "Setup not supported for $SETUP_OS"
      ;;
  esac
}

setup_output(){
  echo "## Tools setup" > $SETUP_OUTPUT
  echo "### os" >> $SETUP_OUTPUT
  uname -a >> $SETUP_OUTPUT
  echo "### kubectl" >> $SETUP_OUTPUT
  kubectl version --client>> $SETUP_OUTPUT
  echo "### tofu" >> $SETUP_OUTPUT
  tofu --version >> $SETUP_OUTPUT
  echo "### java" >> $SETUP_OUTPUT
  java --version >> $SETUP_OUTPUT
  echo "### mvn" >> $SETUP_OUTPUT
  mvn --version >> $SETUP_OUTPUT
  if [ -z "$CLOUD" ]; then
    echo "### aws" >> $SETUP_OUTPUT
    aws --version >> $SETUP_OUTPUT
    echo "### az" >> $SETUP_OUTPUT
    az --version >> $SETUP_OUTPUT
    kubelogin --version >> $SETUP_OUTPUT
    echo "### gcloud" >> $SETUP_OUTPUT
    gcloud --version >> $SETUP_OUTPUT
  fi
  if [ "$CLOUD" == "aws" ]; then
    echo "### aws" >> $SETUP_OUTPUT
    aws --version >> $SETUP_OUTPUT
  fi
  if [ "$CLOUD" == "azure" ]; then
    echo "### az" >> $SETUP_OUTPUT
    az --version >> $SETUP_OUTPUT
    kubelogin --version >> $SETUP_OUTPUT
  fi
  if [ "$CLOUD" == "gcp" ]; then
    echo "### gcloud" >> $SETUP_OUTPUT
    gcloud --version >> $SETUP_OUTPUT
  fi
  echo "Setup output written to $SETUP_OUTPUT"
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
CLI_ARGS=
CLI_COMMAND=
CLI_DIR=
CLOUD=
CONFIG_ENV=
CONFIG_ZIP=
DOCS_DIR=
ENV=
EXEC_CLASS=
EXEC_ARGS=
EXEC_ENVS=
EXEC_PACKAGE=
EXEC_TYPE=
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
SETUP_OS=
SETUP_OUTPUT=
TOFU_ACTION=
TOFU_ARGS=
VERSION=

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
    --cli-args|-ca)
      CLI_ARGS="$2"; shift 2 ;;
    --cli-command|-cc)
      CLI_COMMAND="$2"; shift 2 ;;
    --cli-dir|-cd)
      CLI_DIR="$2"; shift 2 ;;
    --config-env)
      CONFIG_ENV="$2"; shift 2 ;;
    --config-zip)
      CONFIG_ZIP="$2"; shift 2 ;;
    --docs-dir|-dd)
      DOCS_DIR="$2"; shift 2 ;;
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
    --exec-type|-et)
      EXEC_TYPE="$2"; shift 2 ;;
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
    --setup-os)
      SETUP_OS="$2"; shift 2 ;;
    --setup-outout)
      SETUP_OUTPUT="$2"; shift 2 ;;
    --tofu-args|-ta)
      TOFU_ARGS="$2"; shift 2 ;;
    *)
      echo "Unknown option: $1" >&2;
      usage; exit 0
      ;;
  esac
done

AFON_HOME="${AFON_HOME:-}"
if [ -n "$AFON_HOME" ]; then
  HOME_DIR="$AFON_HOME"
fi

if [ -n "$HOME_DIR" ]; then
  echo "Using home dir $HOME_DIR"
else
  HOME_DIR="$HOME/.afon"
  echo "Home dir not specified. Defaulting to $HOME_DIR"
  AFON_HOME="$HOME_DIR"
  echo "Setting AFON_HOME=$AFON_HOME"
fi

AFON_ENV="${AFON_ENV:-}"
if [ -n "$AFON_ENV" ]; then
  ENV="$AFON_ENV"
fi

if [ -n "$ENV" ]; then
  echo "Using env=$ENV"
  echo "Both cdktf and config env are set to $ENV"
  CDKTF_ENV="$ENV"
  CONFIG_ENV="$ENV"
fi

CONFIG_DIR="$HOME_DIR/config"
echo "Using config dir $CONFIG_DIR"

if [ -n "$CONFIG_ENV" ]; then
  exec_config_switch
fi

AFON_CDKTF="${AFON_CDKTF:-}"
if [ -n "$AFON_CDKTF" ]; then
  CDKTF_DIR="$AFON_CDKTF"
fi

if [ -n "$CDKTF_DIR" ]; then
  echo "Using cdktf dir $CDKTF_DIR"
else
  CDKTF_DIR="$HOME_DIR/cdktf"
  echo "Cdktf dir not specified. Defaulting to $CDKTF_DIR"
  AFON_CDKTF="$CDKTF_DIR"
  echo "Setting AFON_CDKTF=$AFON_CDKTF"
fi

if [ -n "$CDKTF_ENV" ]; then
  exec_cdktf_switch
fi

AFON_CLI_DIR=${AFON_CLI_DIR:-}
if [ -n "$AFON_CLI_DIR" ]; then
  CLI_DIR="$AFON_CLI_DIR"
fi

if [ -n "$CLI_DIR" ]; then
  echo "Using cli dir $CLI_DIR"
else
  CLI_DIR="$HOME_DIR/cli"
  echo "Defaulting to $CLI_DIR"
fi

AFON_LOGGING="${AFON_LOGGING:-}"
if [ -n "$AFON_LOGGING" ]; then
  LOGGING="$AFON_LOGGING"
fi

if [ -n "$LOGGING" ]; then
  echo "Using logging level: $LOGGING"
fi

AFON_LOGGING_CONSOLE="${AFON_LOGGING_CONSOLE:-}"
if [ -n "$AFON_LOGGING_CONSOLE" ]; then
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

AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Using AFON_ROOT=$AFON_ROOT"
MVN_CMD="mvn"
if [ -n "$MVN_QUIET" ]; then
  MVN_CMD="$MVN_CMD -q"
fi
MVN_CMD="$MVN_CMD -s $AFON_ROOT/settings.xml"
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
  cdktf-*)
    exec_cdktf
    ;;
  config-*)
    exec_config
    ;;
  docs-export)
    VERSION=$(version)
    if [ -z "$DOCS_DIR" ]; then
      DOCS_DIR="$HOME_DIR/docs"
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
    exec_jvm
    ;;
  script)
    exec_script
    ;;
  setup-*)
    if [ -z "$SETUP_OS" ]; then
      SETUP_OS=$(uname -a| awk '{print $NF}')
    fi
    SETUP_ACTION=${ACTION#setup-}
    case $SETUP_ACTION in
      local)
        setup_local
        ;;
      show)
        if [ -z "$SETUP_OUTPUT" ]; then
          SETUP_OUTPUT="$HOME_DIR/versions.md"
        fi
        setup_output
        ;;
      *)
        echo "Setup action not supported: $SETUP_ACTION"
        usage; exit 0
    esac
    ;;
  tofu-*)
    exec_tofu
    ;;
  *)
    echo "Unknown action: $ACTION"
    ;;
esac
cd $AFON_ROOT

