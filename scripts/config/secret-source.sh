CLOUD=${1:-}
set -a
source $AFON_HOME/config/secret.properties
if [ -n "$CLOUD" ] && [ "$CLOUD" != "ALL" ]; then
  source $AFON_HOME/config/secret-$CLOUD.properties
fi
set +a
