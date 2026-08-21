#!/usr/bin/env bash
set -e

VERSION=$1
QUALIFIER=$2
NEXT=$3

if [ -z "$VERSION" ] || [ -z "$QUALIFIER" ] || [ -z "$NEXT" ] ; then
  echo "usage:  $0 <version> <qualifier> <next>"
  echo "Version:    release version (semver)"
  echo "Qualifier:  stable|preview"
  echo "Next:       next snapshot version"
  echo "Examples:"
  echo "  $0  1.0 stable 1.1-SNAPSHOT"
  echo "  $0  1.0-beta-1 preview 1.0-SNAPSHOT"
  exit;
fi

CWD=`pwd`
MVN="mvn -s $CWD/settings.xml"
PROFILES=cli,distribution
NAME="afon"
TAG="$NAME-$VERSION"

$MVN --batch-mode release:prepare -P$PROFILES -DreleaseVersion=$VERSION -Dtag=$TAG -DdevelopmentVersion=$NEXT
#$MVN release:perform -P$PROFILES

echo "Release $TAG ($QUALIFIER) completed."

