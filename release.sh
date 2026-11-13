#!/usr/bin/env bash
set -e

usage(){
  echo "usage:  $0 [--perform] [--upload] <version> [<next>]"
  echo "version:    release version (semver)"
  echo "next:       next snapshot version"
  echo "options:"
  echo "  --perform   invoke release:perform (default false)"
  echo "  --upload    invoke ./upload.sh on the distribution zip (default false)"
  echo "Without options, the release is only prepared: the git tag is created and pushed, and the next snapshot version is returned."
  echo "examples:"
  echo "  $0  1.0 1.1-SNAPSHOT"
  echo "  $0  --perform --upload 1.0 1.1-SNAPSHOT"
  echo "  $0  1.0-beta-1 [1.0-SNAPSHOT]"
}

PERFORM=false
UPLOAD=false
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --perform) PERFORM=true ;;
    --upload) UPLOAD=true ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1"; usage; exit 1 ;;
    *) ARGS+=("$1") ;;
  esac
  shift
done

VERSION=${ARGS[0]}
NEXT=${ARGS[1]}

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
echo "Next snapshot version is $NEXT"

$MVN --batch-mode release:prepare -P$PROFILES -DreleaseVersion=$VERSION -Dtag=$TAG -DdevelopmentVersion=$NEXT
echo "Release $TAG prepared."

if [ "$PERFORM" == "true" ]; then
  # release:perform is usually done in GitHub's release workflow
  echo "Performing release $TAG."
  $MVN release:perform -P$PROFILES
  MAVEN_DEPLOY_SKIP=$(mvn help:evaluate -Dexpression=maven.deploy.skip -q -DforceStdout)
  if [ "$MAVEN_DEPLOY_SKIP" == "true" ]; then
    echo "Maven deploy has been skipped"
  fi
fi

echo "Pushing git refs"
git push origin main
git push origin $TAG

if [ "$PERFORM" != "true" ]; then
  # release:prepare leaves release.properties and the pom.xml.releaseBackup files for release:perform, which is not
  # run here: clean them up so that the git tree is left clean
  echo "Cleaning up the release files"
  $MVN release:clean -P$PROFILES
fi

if [ "$UPLOAD" == "true" ]; then
  # the upload is usually done by GitHub's release workflow
  DISTRIBUTION_ZIP="$CWD/afon-distribution/target/afon-$VERSION-cli.zip"
  echo "Uploading $DISTRIBUTION_ZIP"
  $CWD/upload.sh $DISTRIBUTION_ZIP
fi


