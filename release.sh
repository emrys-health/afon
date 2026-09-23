#!/usr/bin/env bash
set -e

usage(){
  echo "usage:  $0 <version> [<next>]"
  echo "version:    release version (semver)"
  echo "next:       next snapshot version"
  echo "examples:"
  echo "  $0  1.0 1.1-SNAPSHOT"
  echo "  $0  1.0-beta-1 [1.0-SNAPSHOT]"
}

VERSION=$1
NEXT=$2

if [ -z "$VERSION" ]; then
  usage;
  exit 0;
fi
if [ -z "$NEXT" ]; then
  NEXT=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
fi

CWD=`pwd`
MVN="mvn -q -s $CWD/settings.xml"
PROFILES="cli,distribution"
NAME="afon"

RELEASE_NOTES="$CWD/release_notes.md"
echo "Updating release notes $RELEASE_NOTES from milestone $VERSION"
$CWD/scripts/github/milestones.sh $VERSION $RELEASE_NOTES --state closed
# release:prepare requires no local modifications, so commit the notes if they changed
if ! git diff --quiet -- $RELEASE_NOTES; then
  git commit -m "Updated release notes for $VERSION" -- $RELEASE_NOTES
fi

TAG="$NAME-$VERSION"
echo "Preparing release $TAG."

$MVN --batch-mode release:prepare -P$PROFILES -DreleaseVersion=$VERSION -Dtag=$TAG -DdevelopmentVersion=$NEXT
$MVN release:perform -P$PROFILES

echo "Release $TAG completed."
git push --tags

MAVEN_DEPLOY_SKIP=$(mvn help:evaluate -Dexpression=maven.deploy.skip -q -DforceStdout)
if [ "$MAVEN_DEPLOY_SKIP" == "true" ]; then
  echo "Maven deploy has been skipped"
fi

DISTRIBUTION_ZIP="$CWD/afon-distribution/target/afon-$VERSION-cli.zip"
echo "Uploading $DISTRIBUTION_ZIP"
$CWD/upload.sh $DISTRIBUTION_ZIP

