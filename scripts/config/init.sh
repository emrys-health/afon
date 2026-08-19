CONFIG_DIR=$AFON_HOME/config
mkdir -p $CONFIG_DIR

source $AFON_ROOT/scripts/config/config-files.sh
echo "Init config files in $CONFIG_DIR"
for FILE in $CONFIG_FILES ; do {
  echo $FILE
  cp -np $AFON_ROOT/templates/config/$FILE $CONFIG_DIR || echo "file exists: not overwriting"
} done
echo "Generated empty config files from templates, which need to completed before proceeding."

