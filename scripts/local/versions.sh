# --versions-output is only a flag on afon.sh (which sets VERSIONS_OUTPUT before sourcing this script);
# afon-cli-scripts.sh/afon-cli.sh users pass it as a plain script-arg instead, eg
# 'afon-cli.sh local-versions <output-file>' - same fallback pattern as config/export.sh's zip path.
VERSIONS_OUTPUT="${VERSIONS_OUTPUT:-${1:-}}"
if [ -z "$VERSIONS_OUTPUT" ]; then
  VERSIONS_OUTPUT="$AFON_HOME/versions.md"
fi

# CLOUD is "ALL" (afon-cli.sh's default) or empty (afon.sh's default) when no cloud is targeted.
if [ "$CLOUD" == "ALL" ]; then
  CLOUD=
fi

source $AFON_ROOT/scripts/local/pinned.sh

echo "## Local setup" > $VERSIONS_OUTPUT
echo "### os" >> $VERSIONS_OUTPUT
uname -a >> $VERSIONS_OUTPUT
echo "### kubectl" >> $VERSIONS_OUTPUT
kubectl version --client>> $VERSIONS_OUTPUT
echo "### tofu" >> $VERSIONS_OUTPUT
tofu --version >> $VERSIONS_OUTPUT
echo "### java" >> $VERSIONS_OUTPUT
java --version >> $VERSIONS_OUTPUT
echo "### mvn" >> $VERSIONS_OUTPUT
mvn --version >> $VERSIONS_OUTPUT
echo "### jq" >> $VERSIONS_OUTPUT
jq --version >> $VERSIONS_OUTPUT
echo "### yq" >> $VERSIONS_OUTPUT
yq --version >> $VERSIONS_OUTPUT
echo "### helm" >> $VERSIONS_OUTPUT
helm version --short >> $VERSIONS_OUTPUT
echo "### kind" >> $VERSIONS_OUTPUT
kind version >> $VERSIONS_OUTPUT
echo "### cosign" >> $VERSIONS_OUTPUT
cosign version >> $VERSIONS_OUTPUT
if [ -z "$CLOUD" ]; then
  echo "### aws" >> $VERSIONS_OUTPUT
  aws --version >> $VERSIONS_OUTPUT
  echo "### az" >> $VERSIONS_OUTPUT
  az --version >> $VERSIONS_OUTPUT
  kubelogin --version >> $VERSIONS_OUTPUT
  echo "### gcloud" >> $VERSIONS_OUTPUT
  gcloud --version >> $VERSIONS_OUTPUT
  gke-gcloud-auth-plugin --version >> $VERSIONS_OUTPUT
fi
if [ "$CLOUD" == "aws" ]; then
  echo "### aws" >> $VERSIONS_OUTPUT
  aws --version >> $VERSIONS_OUTPUT
fi
if [ "$CLOUD" == "azure" ]; then
  echo "### az" >> $VERSIONS_OUTPUT
  az --version >> $VERSIONS_OUTPUT
  kubelogin --version >> $VERSIONS_OUTPUT
fi
if [ "$CLOUD" == "gcp" ]; then
  echo "### gcloud" >> $VERSIONS_OUTPUT
  gcloud --version >> $VERSIONS_OUTPUT
  gke-gcloud-auth-plugin --version >> $VERSIONS_OUTPUT
fi
echo "### pinned" >> $VERSIONS_OUTPUT
check_tool_versions $(pinned_tools) | tee -a $VERSIONS_OUTPUT
PINNED_RC=${PIPESTATUS[0]}
echo "Versions output written to $VERSIONS_OUTPUT"
if [ $PINNED_RC -ne 0 ]; then
  echo "Installed versions differ from the pinned ones (scripts/local/pinned.sh)"
fi
