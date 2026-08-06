CONFIG_DIR=~/.afon/dev/config
# shellcheck disable=SC2045
for TEMPLATE in $(ls ../../templates/config/*) ; do {
  FILE_NAME=$(basename $TEMPLATE)
  export CONFIG_FILE=$CONFIG_DIR/$FILE_NAME
  if [ -z $CONFIG_FILE ]; then
     echo "Missing config file $FILE_NAME in $CONFIG_DIR"
  fi
  echo "Validating config file $FILE_NAME"
  for KEY in $(grep '=' $TEMPLATE | grep -v ^\# | grep -e ^[A-Z] | perl -pe s/=.*//g) ; do {
    grep ^$KEY= $CONFIG_FILE > /dev/null || echo "no $KEY in $CONFIG_FILE" ;
  } done
} done