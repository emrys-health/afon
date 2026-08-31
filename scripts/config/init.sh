INIT_DIR=
if [ -n "$CONFIG_ENV" ]; then
  INIT_DIR="$CONFIG_DIR-$CONFIG_ENV"
else
  INIT_DIR="$CONFIG_DIR"
fi
mkdir -p $INIT_DIR

INIT_ENV=${CONFIG_ENV:-}
INIT_MD="$INIT_DIR/afon.md"
if [ ! -f "$INIT_MD" ]; then
  echo "# Afon $INIT_ENV config" > $INIT_MD
fi

source $AFON_ROOT/scripts/config/config-files.sh
echo "Generating config files in $INIT_DIR"
for FILE in $CONFIG_FILES ; do {
  echo $FILE
  cp -np $AFON_ROOT/templates/config/$FILE $INIT_DIR || echo "file exists: not overwriting"
} done
echo "Generated config files from templates, which need to completed before proceeding."

if [ -n "$CONFIG_ENV" ]; then
  echo "The config dir for env '$CONFIG_ENV' has been created."
  echo "To switch to env '$CONFIG_ENV':"
  echo "./afon.sh -a config-switch --config-env $CONFIG_ENV"
fi
