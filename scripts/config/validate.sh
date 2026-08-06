CONFIG_DIR="$AFON_HOME/config"
if [ ! -d $CONFIG_DIR ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit;
fi
# shellcheck disable=SC2045
for TEMPLATE in $(ls $CWD/templates/config/*) ; do {
  FILE_NAME=$(basename $TEMPLATE)
  export CONFIG_FILE=$CONFIG_DIR/$FILE_NAME
  if [ -z $CONFIG_FILE ]; then
     echo "Missing config file $FILE_NAME in $CONFIG_DIR"
  fi
  echo "Validating config file $FILE_NAME"
  for KEY in $(grep '=' $TEMPLATE | grep -v ^\# | grep -e ^[A-Z] | perl -pe s/=.*//g) ; do {
    grep ^$KEY= $CONFIG_FILE > /dev/null || echo "no key $KEY" ;
    grep -E '^[^=]+=[^[:space:]]+' $CONFIG_FILE > /dev/null || echo "no value for key $KEY"
  } done
} done