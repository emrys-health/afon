#!/usr/bin/env bash
set -euo pipefail

usage (){
  echo "Usage: $0 [--action|-a <action>] [--cloud|-c <cloud>] [--dry-run] [--mvn-quiet] [--help|-h]"
  echo "          [--logging-level|-l <logging>]"
  echo "          [--build-args <args>] [--build-goals|-g <goals>] [--build-profiles|-p <profiles>]"
  echo "          [--docs-dir|-d <dir>]"
  echo "          [--exec-package <package>] [--exec-class <class>] [--exec-args <args>] [--exec-envs <envs>] [--exec-type|-t <types>]"
  echo "          [--exec-script <script-name>]"
  echo "          [--setup-os <os>]"
  echo "          [--tofu-apply] [--tofu-destroy] [--tofu-state] [--tofu-args]"
  echo "actions:  build|docs|exec|setup|tofu-{apply|destroy}"
  echo "          build :  executes the mvn build"
  echo "          docs  :  retrieves the docs that have been generated in the build and unpacks them to afon-docs/target/ops"
  echo "          exec  :  executes the jvm classes from ops (see the docs for details)"
  echo "          setup :  sets up the local env for the given os."
  echo "          tofu-*:  executes the tofu commands.  One of apply|destroy|state"
  echo "cloud:          the cloud to target. One of aws|azure|gcp. If not specified all are targetted."
  echo "logging-level:  the logging level.  One of DEBUG|INFO|WARN|ERROR.  Defaults to logback configuration."
  echo "build-args:     the build args.  eg '-DskipTests'."
  echo "build-goals:    the build goals.  Defaults to 'clean install'."
  echo "build-profiles: the build profiles.  One of acceptance|ops|samples.  If not specified all are active."
  echo "docs-dir:       the output dir for the docs."
  echo "exec-types:     the exec types.  One of ops|samples|script.  Defaults to 'ops'."
  echo "exec-package:   the jvm base package of the class to execute.  Defaults to 'health.emrys.afon'"
  echo "exec-class:     the jvm class to execute.  Can be absolute or relative to the exec package.  Required for exec types 'ops' and 'samples'."
  echo "exec-args:      the jvm exec args (must be quoted if more than one arg)"
  echo "exec-envs:      the jvm exec envs as key=value pairs (must be quoted if more than one pair)"
  echo "setup-os:       the os to setup.  One of linux|macos"
  echo "tofu-args:      the tofu args."
  echo "mvn-quiet:      the flag to hush the mvn output"
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

mvn_envs(){
  MVN_ENVS="-Dafon.home=$CWD"
  if [ -n "$CLOUD" ] ; then
    MVN_ENVS="$MVN_ENVS -Dafon.cloud=$CLOUD"
  fi
  if [ -n "$LOGGING" ] ; then
    MVN_ENVS="$MVN_ENVS -Dafon.logging=$LOGGING"
  fi
}

exec_mvn() {
  if [ -n "$MVN_ARGS" ] ; then
    echo "Executing $MVN_CMD \"$MVN_ARGS\" $MVN_ENVS $MVN_GOALS"
    $MVN_CMD "$MVN_ARGS" $MVN_ENVS $MVN_GOALS
  else
    echo "Executing $MVN_CMD $MVN_ENVS $MVN_GOALS"
    $MVN_CMD $MVN_ENVS $MVN_GOALS
  fi
}

exec_jvm(){
    if [ -z "$EXEC_PACKAGE" ] ; then
      EXEC_PACKAGE="health.emrys.afon"
    fi
  if [ -z "$EXEC_CLASS" ] ; then
    echo "Action exec requires option --exec-class"
    usage; exit 1
  fi
  if [ -n "$EXEC_PACKAGE" ] ; then
    if [[ "$EXEC_CLASS" != "$EXEC_PACKAGE"* ]]; then
      EXEC_CLASS="$EXEC_PACKAGE.$EXEC_CLASS"
    fi
  fi
  MVN_GOALS="exec:exec -Pexec -Dexec.mainClass=$EXEC_CLASS"
  if [ -n "$EXEC_ARGS" ] ; then
    MVN_ARGS="-Dexec.args=\"$EXEC_ARGS\""
  fi
  if [ -n "$EXEC_ENVS" ] ; then
    EXEC_ENVS="${EXEC_ENVS/ / -D}"
    MVN_ENVS="$MVN_ENVS -D$EXEC_ENVS"
  fi
  if [ -z "$EXEC_TYPE" ] ; then
    echo "Action exec requires option --exec-type"
    usage; exit 1
  fi
  cd $CWD/afon-$EXEC_TYPE
  exec_mvn
}

exec_script(){
  if [ -z "$EXEC_SCRIPT" ] ; then
    echo "Action exec requires option --exec-script"
    usage; exit 1
  fi
  if [ -z "$CLOUD" ] ; then
    echo "Script requires option --cloud"
    usage; exit 1
  fi
  set -a && source $CWD/config/afon.properties && source $CWD/config/afon-$CLOUD.properties && set +a
  $CWD/scripts/$CLOUD/$EXEC_SCRIPT
}

exec_tofu(){
  TOFU_DIR="afon-ops/cdktf.out/stacks/$CLOUD"
  if [ ! -d "$TOFU_DIR" ] ; then
     echo "Dir $TOFU_DIR not found.  Execute deploy or configure actions first."
     usage; exit 0
  fi
  TOFU_ACTION=${ACTION#tofu-}
  if [ "$TOFU_ACTION" != "state" ] ; then
    TOFU_ARGS="$TOFU_ARGS -auto-approve"
  fi
  TOFU_CMD="tofu -chdir=$TOFU_DIR $TOFU_ACTION $TOFU_ARGS"
  echo "Executing $TOFU_CMD"
  $TOFU_CMD
}

setup_env(){
  case $SETUP_OS in
    linux)
      sudo apt install git openjdk-17-jdk maven docker tofu kubectl awscli azure-cli google-cloud-cli
      # install eksctl hubble via tarball?
      ;;
    macos)
      brew install git openjdk@17 maven docker opentofu kubernetes-cli awscli azure-cli gcloud-cli
      brew install eksctl hubble
      ;;
    docker)

      ;;
    *)
      echo "Setup not supported for $SETUP_OS"
      ;;
  esac
}

version(){
  mvn help:evaluate -Dexpression=project.version -q -DforceStdout
}

ACTION=
BUILD_ARGS=
BUILD_GOALS=
BUILD_PROFILES=
CLOUD=
LOGGING=
DRY_RUN=false
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
TOFU_ARGS=
VERSION=

while [[ $# -gt 0 ]]; do
  case "$1" in
    --action|-a)
      ACTION="$2"; shift 2 ;;
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
    --setup-os)
      SETUP_OS="$2"; shift 2 ;;
    --tofu-args)
      TOFU_ARGS="$2"; shift 2 ;;
    --mvn-quiet)
      MVN_QUIET=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2;
      usage; exit 1 ;;
  esac
done

if [ "$ACTION" == "" ] ; then
  ACTION="build"
  echo "Action not specified. Defaulting to $ACTION"
fi

if [ -n "$BUILD_PROFILES" ] ; then
  echo "Using build profiles $BUILD_PROFILES"
  MVN_PROFILES="-P$BUILD_PROFILES"
fi

if [ -n "$CLOUD" ] ; then
  echo "Using cloud $CLOUD"
    if [ -n "$MVN_PROFILES" ] ; then
      MVN_PROFILES="$MVN_PROFILES,$CLOUD"
    else
      MVN_PROFILES="-P$CLOUD"
    fi
fi

CWD=`pwd`
MVN_CMD="mvn"
if [ -n "$MVN_QUIET" ] ; then
  MVN_CMD="$MVN_CMD -q"
fi
MVN_CMD="$MVN_CMD -s $CWD/settings.xml $MVN_PROFILES"

case $ACTION in

  build)
    if [ -n "$BUILD_GOALS" ] ; then
      MVN_GOALS="$BUILD_GOALS"
    else
      MVN_GOALS="clean install"
    fi
    if [ -n "$BUILD_ARGS" ] ; then
      MVN_ARGS="$BUILD_ARGS"
    fi
    mvn_envs
    exec_mvn
    check_acceptance
    ;;
  docs)
    VERSION=$(version)
    if [ -z "$DOCS_DIR" ]; then
      DOCS_DIR=afon-docs/target/docs
    fi
    mkdir -p $DOCS_DIR
    ARTIFACT="health.emrys:afon-docs:$VERSION:zip:docs"
    mvn -q dependency:copy -Dartifact=$ARTIFACT -DoutputDirectory=$DOCS_DIR
    unzip -qu $DOCS_DIR/afon-docs-$VERSION-docs.zip -d $DOCS_DIR
    echo "Copied $ARTIFACT to $DOCS_DIR"
    ;;
  exec)
    if [ -n "$EXEC_TYPE" ] ; then
      echo "Using exec type '$EXEC_TYPE'"
    else
      echo "Exec type not specified.  Defaulting to 'ops'."
      EXEC_TYPE="ops"
    fi
    if [ "$EXEC_TYPE" == "script" ] ; then
      exec_script
    else
      mvn_envs
      exec_jvm
    fi
    ;;
  setup)
    setup_env
    ;;
  tofu-*)
    if [ -z "$CLOUD" ]; then
      CLOUD="aws"
      echo "Defaulting to cloud $CLOUD"
    fi
    exec_tofu
    ;;
  *)
    echo "Unknown action $ACTION"
    ;;
esac
cd $CWD

