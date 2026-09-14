EXPORT_DIR=
if [ -n "$CONFIG_ENV" ]; then
  EXPORT_DIR="$CONFIG_DIR-$CONFIG_ENV"
else
  EXPORT_DIR="$CONFIG_DIR"
fi

if [ -z "$CONFIG_ZIP" ]; then
  echo "Action $ACTION requires option --config-zip"
  usage; exit 0
fi
EXPORT_ZIP=$AFON_HOME/$CONFIG_ZIP
if [ -d $CONFIG_DIR ]; then
  zip -jr $EXPORT_ZIP $EXPORT_DIR
  echo "Exported $EXPORT_DIR to $EXPORT_ZIP."
else
  echo "Dir $EXPORT_DIR not found."
fi
