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
echo "Versions output written to $VERSIONS_OUTPUT"
