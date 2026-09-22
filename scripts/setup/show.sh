# --setup-output is only a flag on afon.sh (which sets SETUP_OUTPUT before sourcing this script);
# afon-cli-scripts.sh/afon-cli.sh users pass it as a plain script-arg instead, eg
# 'afon-cli.sh setup-show <output-file>' - same fallback pattern as config/export.sh's zip path.
SETUP_OUTPUT="${SETUP_OUTPUT:-${1:-}}"
if [ -z "$SETUP_OUTPUT" ]; then
  SETUP_OUTPUT="$AFON_HOME/versions.md"
fi

# CLOUD is "ALL" (afon-cli.sh's default) or empty (afon.sh's default) when no cloud is targeted.
if [ "$CLOUD" == "ALL" ]; then
  CLOUD=
fi

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
