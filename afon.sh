#!/usr/bin/env bash
set -euo pipefail

exec_mvn () {
  if [ -n "$MVN_ARGS" ] ; then
    echo "Executing $MVN_CMD \"$MVN_ARGS\" $MVN_ENVS $MVN_GOALS"
    $MVN_CMD "$MVN_ARGS" $MVN_ENVS $MVN_GOALS
  else
    echo "Executing $MVN_CMD $MVN_ENVS $MVN_GOALS"
    $MVN_CMD $MVN_ENVS $MVN_GOALS
  fi
}

usage (){
  echo "Usage: $0 [--action|-a <action>] [--profiles|-p <profiles>] [--dry-run] [--help|-h]"
  echo "          [--exec-class <mainClass>]"
  echo "actions: build|exec"
}

ACTION="build"
PROFILES=
DRY_RUN=false
EXEC_CLASS=
EXEC_ARGS=
EXEC_ENVS=
MVN_PROFILES=
MVN_GOALS=
MVN_ARGS=
MVN_ENVS=

while [[ $# -gt 0 ]]; do
  case "$1" in
    --action|-a)
      ACTION="$2"; shift 2 ;;
    --profiles|-p)
      PROFILES="$2"; shift 2 ;;
    --dry-run)
      DRY_RUN=true; shift ;;
    --exec-class)
      EXEC_CLASS="$2"; shift 2 ;;
    --exec-args)
      EXEC_ARGS="$2"; shift 2 ;;
    --exec-envs)
      EXEC_ENVS="$2"; shift 2 ;;
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

CWD=`pwd`
MVN_CMD="mvn -s $CWD/settings.xml $MVN_PROFILES"

case $ACTION in

  build)
    MVN_GOALS="clean install"
    exec_mvn
    ;;
  exec)
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
    cd $CWD/afon-demo
    exec_mvn
    ;;
  *)
    echo "Unknown action $ACTION"
    ;;
esac
cd $CWD

