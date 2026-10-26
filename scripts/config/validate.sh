CONFIG_DIR="$AFON_HOME/config"
if [ ! -d $CONFIG_DIR ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit 1
fi
source $AFON_ROOT/scripts/config/config-files.sh
echo "Validating config files in $CONFIG_DIR"
# Missing files fail the validation, as a run cannot do without them.  Missing keys or values are only
# warned about, as not every key is needed by every run (eg those of a cloud or feature not in use).
MISSING=0
for FILE in $CONFIG_FILES ; do {
  echo "$FILE"
  TEMPLATE=$AFON_ROOT/templates/config/$FILE
  CONFIG_FILE=$CONFIG_DIR/$FILE
  if [ ! -f $CONFIG_FILE ] && [ "$FILE" = "whitelist.yaml" ] ; then
     # not mandatory yet: without it the deprecated ALLOWED_DOMAINS_CSV is used, if set, else no domains are allowed
     echo "Missing: $FILE (optional): run config-updates to create it from the deprecated ALLOWED_DOMAINS_CSV, or copy templates/config/$FILE"
     continue
  fi
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
# Catalogs of a cloud sharing a bucket fail the validation, as Polaris rejects creating a catalog whose location
# overlaps an existing catalog's.  Glue catalogs are excluded: being federated, they reuse an external catalog's bucket.
SHARED=0
for CATALOGS_FILE in $CONFIG_DIR/catalogs-*.properties ; do {
  [ -f $CATALOGS_FILE ] || continue
  CLOUD=$(basename $CATALOGS_FILE .properties | perl -pe 's/^catalogs-//; $_ = uc')
  IDS=$(grep "^${CLOUD}_CATALOG_IDS=" $CATALOGS_FILE | cut -d= -f2 | tr ',' ' ' || true)
  BUCKET_IDS=$(for ID in $IDS ; do {
    if grep -qE "^${CLOUD}_CATALOG_TYPE_$ID=glue[[:space:]]*$" $CATALOGS_FILE ; then
      continue
    fi
    BUCKET=$(grep "^${CLOUD}_BUCKET_NAME_$ID=" $CATALOGS_FILE | cut -d= -f2 | tr -d '[:space:]' || true)
    if [ -n "$BUCKET" ] ; then
      echo "$BUCKET $ID"
    fi
  } done)
  for BUCKET in $(echo "$BUCKET_IDS" | awk 'NF { print $1 }' | sort | uniq -d) ; do {
    echo "Shared bucket $BUCKET in $(basename $CATALOGS_FILE): catalogs $(echo "$BUCKET_IDS" | awk -v b=$BUCKET '$1 == b { print $2 }' | paste -sd, -)"
    SHARED=$((SHARED + 1))
  } done
} done
echo "NOTE: If validation fails, it may be useful to compare config dir with a previous config: diff --colour dir1 dir2"
if [ $MISSING -gt 0 ] || [ $SHARED -gt 0 ]; then
  [ $MISSING -gt 0 ] && echo "Validation failed: $MISSING config file(s) missing"
  [ $SHARED -gt 0 ] && echo "Validation failed: $SHARED bucket(s) shared by catalogs"
  exit 1
fi
