CLOUD=${1:-}
set -a
source $AFON_HOME/config/afon.properties
if [ -n "$CLOUD" ]; then
  source $AFON_HOME/config/afon-$CLOUD.properties
fi
set +a
