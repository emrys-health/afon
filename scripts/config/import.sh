IMPORT_DIR=
if [ -n "$CONFIG_ENV" ]; then
  IMPORT_DIR="$CONFIG_DIR-$CONFIG_ENV"
else
  IMPORT_DIR="$CONFIG_DIR"
fi
if [ -z "$CONFIG_ZIP" ]; then
  echo "Action $ACTION requires option --config-zip"
  usage; exit 0
fi
if [ -d $IMPORT_DIR ]; then
  echo "$IMPORT_DIR already exists.  Remove and retry."
  exit 0
fi
if [ -f $CONFIG_ZIP ]; then
  mkdir -p $IMPORT_DIR
  unzip -jqu $CONFIG_ZIP -d $IMPORT_DIR
  echo "Imported $CONFIG_ZIP to $IMPORT_DIR."
else
  echo "File $CONFIG_ZIP not found."
fi

if [ -n "$CONFIG_ENV" ]; then
  echo "The config dir for env '$CONFIG_ENV' has been imported."
  echo "To switch to env '$CONFIG_ENV':"
  echo "./afon.sh -a config-switch --config-env $CONFIG_ENV"
fi
