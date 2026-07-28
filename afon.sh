#!/usr/bin/env bash
set -euo pipefail

check_acceptance(){
  SCENARIOS_FILE="afon-acceptance/target/surefire-reports/scenarios.txt"
  if [ -f "$SCENARIOS_FILE" ]; then
    echo "Checking all acceptance specs have been run in $SCENARIOS_FILE"
    for SPEC in $(find afon-acceptance -name \*Spec.scala 2>/dev/null | perl -pe s/.*\\///g | perl -pe s/.scala//g | grep -v Abstract) ; do {
      grep $SPEC $SCENARIOS_FILE >/dev/null || echo "$SPEC has not been run"
    } done
  fi
}

exec_mvn () {
  if [ -n "$MVN_ARGS" ] ; then
    echo "Executing $MVN_CMD \"$MVN_ARGS\" $MVN_ENVS $MVN_GOALS"
    $MVN_CMD "$MVN_ARGS" $MVN_ENVS $MVN_GOALS
  else
    echo "Executing $MVN_CMD $MVN_ENVS $MVN_GOALS"
    $MVN_CMD $MVN_ENVS $MVN_GOALS
  fi
}

exec_jvm(){
      if [ -z "$EXEC_CLASS" ] ; then
        echo "Action exec requires option --exec-class"
        usage; exit 1
      fi
      MVN_GOALS="exec:exec -Pexec -Dexec.mainClass=$EXEC_CLASS"
      if [ -n "$EXEC_ARGS" ] ; then
        MVN_ARGS="-Dexec.args=\"$EXEC_ARGS\""
      fi
      if [ -n "$EXEC_ENVS" ] ; then
        MVN_ENVS="-D$EXEC_ENVS"
        MVN_ENVS="${MVN_ENVS/ / -D}"
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

version(){
  mvn help:evaluate -Dexpression=project.version -q -DforceStdout
}

usage (){
  echo "Usage: $0 [--action|-a <action>] [--profiles|-p <profiles>] [--cloud|-c <cloud>] [--dry-run] [--help|-h]"
  echo "          [--docs-dir|-d <dir>]"
  echo "          [--exec-class <class>] [--exec-args <args>] [--exec-envs <envs>] [--exec-type|-t <types>]"
  echo "          [--exec-script <script-name>]"
  echo "actions:  build|docs|exec"
  echo "          build: executes the mvn build"
  echo "          docs:  retrieves the docs that have been generated in the build and unpacks them to afon-docs/target/ops"
  echo "          exec:  executes the jvm classes from ops (see the docs for details)"
  echo "profiles: acceptance|ops|samples"
  echo "cloud:    aws|azure|gcp"
  echo "types:    ops|samples|script"
}

ACTION=
PROFILES=
CLOUD=
DRY_RUN=false
DOCS_DIR=
EXEC_CLASS=
EXEC_ARGS=
EXEC_ENVS=
EXEC_SCRIPT=
EXEC_TYPE=
MVN_PROFILES=
MVN_GOALS=
MVN_ARGS=
MVN_ENVS=
VERSION=

while [[ $# -gt 0 ]]; do
  case "$1" in
    --action|-a)
      ACTION="$2"; shift 2 ;;
    --profiles|-p)
      PROFILES="$2"; shift 2 ;;
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
    --exec-script)
      EXEC_SCRIPT="$2"; shift 2 ;;
    --exec-type|-t)
      EXEC_TYPE="$2"; shift 2 ;;
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

if [ -n "$PROFILES" ] ; then
  echo "Using profiles $PROFILES"
  MVN_PROFILES="-P$PROFILES"
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
MVN_CMD="mvn -s $CWD/settings.xml $MVN_PROFILES"

case $ACTION in

  build)
    MVN_GOALS="clean install"
    MVN_ENVS="$MVN_ENVS -Dafon.home=$CWD"
    if [ -n "$CLOUD" ] ; then
      MVN_ENVS="-Dafon.cloud=$CLOUD"
    fi
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
      exec_jvm
    fi
    ;;
  *)
    echo "Unknown action $ACTION"
    ;;
esac
cd $CWD

