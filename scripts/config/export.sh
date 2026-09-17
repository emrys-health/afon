EXPORT_DIR=
if [ -n "$CONFIG_ENV" ]; then
  EXPORT_DIR="$CONFIG_DIR-$CONFIG_ENV"
else
  EXPORT_DIR="$CONFIG_DIR"
fi

CONFIG_ZIP="${CONFIG_ZIP:-${1:-}}"
if [ -z "$CONFIG_ZIP" ]; then
  echo "Action $ACTION requires the config zip path, eg 'afon.sh -a config-export --config-zip <zip>' or 'afon-cli-scripts.sh config/export.sh <zip>'"
  usage; exit 0
fi
EXPORT_ZIP=$AFON_HOME/$CONFIG_ZIP
if [ -d $CONFIG_DIR ]; then
  zip -jr $EXPORT_ZIP $EXPORT_DIR
  echo "Exported $EXPORT_DIR to $EXPORT_ZIP."
else
  echo "Dir $EXPORT_DIR not found."
fi
