CONFIG_DIR="$AFON_HOME/config"
if [ ! -d $CONFIG_DIR ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit 1
fi
source $AFON_ROOT/scripts/config/config-files.sh
# The generic catalogs.properties is not among the files listed by config-files.sh, but is needed just the same
# as the catalogs-<cloud>.properties that are.
CONFIG_FILES="$CONFIG_FILES catalogs.properties"
echo "Validating config files in $CONFIG_DIR"
# Missing files fail the validation, as a run cannot do without them.  Missing keys or values are only
# warned about, as not every key is needed by every run (eg those of a cloud or feature not in use).
MISSING=0
for FILE in $CONFIG_FILES ; do {
  echo "$FILE"
  TEMPLATE=$AFON_ROOT/templates/config/$FILE
  CONFIG_FILE=$CONFIG_DIR/$FILE
  if [ ! -f $CONFIG_FILE ]; then
     echo "Missing: $FILE"
     MISSING=$((MISSING + 1))
     continue
  fi
  for KEY in $(grep '=' $TEMPLATE | grep -v ^\# | grep -e ^[A-Z] | perl -pe s/=.*//g) ; do {
    if ! grep -q "^$KEY=" $CONFIG_FILE ; then
      echo "no key $KEY"
    elif ! grep -qE "^$KEY=[^[:space:]]+" $CONFIG_FILE ; then
      echo "no value for key $KEY"
    fi
  } done
} done
echo "NOTE: If validation fails, it may be useful to compare config dir with a previous config: diff --colour dir1 dir2"
if [ $MISSING -gt 0 ]; then
  echo "Validation failed: $MISSING config file(s) missing"
  exit 1
fi
