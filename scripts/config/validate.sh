CONFIG_DIR="$AFON_HOME/config"
if [ ! -d $CONFIG_DIR ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit;
fi
source $AFON_ROOT/scripts/config/config-files.sh
echo "Validating config files in $CONFIG_DIR"
for FILE in $CONFIG_FILES ; do {
  echo "$FILE"
  TEMPLATE=$AFON_ROOT/templates/config/$FILE
  CONFIG_FILE=$CONFIG_DIR/$FILE
  if [ ! -f $CONFIG_FILE ]; then
     echo "Missing: $FILE"
  fi
  for KEY in $(grep '=' $TEMPLATE | grep -v ^\# | grep -e ^[A-Z] | perl -pe s/=.*//g) ; do {
    grep ^$KEY= $CONFIG_FILE > /dev/null || echo "no key $KEY" ;
    grep -E '^[^=]+=[^[:space:]]+' $CONFIG_FILE > /dev/null || echo "no value for key $KEY"
  } done
} done
echo "NOTE: If validation fails, it may be useful to compare config dir with a previous config: diff --colour dir1 dir2"
