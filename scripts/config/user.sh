if [ -z "$AFON_HOME" ]; then
  echo "AFON_HOME is not set"
  exit 1
fi

CONFIG_DIR="$AFON_HOME/config"
if [ ! -d "$CONFIG_DIR" ]; then
  echo "Missing config dir $CONFIG_DIR"
  exit 1
fi

SECRET_FILE="$CONFIG_DIR/secret.properties"
USERS_FILE="$CONFIG_DIR/users.csv"
USERS_ACL_FILE="$CONFIG_DIR/users-acl.csv"
if [ ! -f "$SECRET_FILE" ]; then
  echo "Missing $SECRET_FILE"
  exit 1
fi

# 1. Updates secret.properties to the current user config, in which the configured user is named by
# USER_ID and its password (and email) are read from users.csv:
#  - USER_POLARIS_ID is renamed to USER_ID, or removed if USER_ID is already defined;
#  - USER_POLARIS_SECRET and USER_POLARIS_EMAIL are removed, along with their template comments.
# Safe to run more than once: the file is backed up first (as <file>.bak) and the backup is
# removed again if nothing turned out to change.
BACKUP="$SECRET_FILE.bak"
cp -p "$SECRET_FILE" "$BACKUP"
if grep -qE '^[[:space:]]*USER_ID[[:space:]]*=' "$SECRET_FILE"; then
  perl -ni -e 'print unless /^\s*USER_POLARIS_ID\s*=/' "$SECRET_FILE"
else
  perl -pi -e 's/^(\s*)USER_POLARIS_ID(\s*=)/${1}USER_ID${2}/' "$SECRET_FILE"
fi
perl -ni -e 'print unless /^\s*USER_POLARIS_(SECRET|EMAIL)\s*=/' "$SECRET_FILE"
perl -ni -e 'print unless /^\s*# Optional verified email\b.*oauth2-proxy/' "$SECRET_FILE"
perl -pi -e 's/^# Polaris User\s*$/# User that clients authenticate as, eg to get its OIDC token.\n# One of the users in users.csv, whose password is read from there.\n/' "$SECRET_FILE"
if cmp -s "$SECRET_FILE" "$BACKUP"; then
  rm "$BACKUP"
  echo "$(basename "$SECRET_FILE"): up to date"
else
  echo "$(basename "$SECRET_FILE"): updated (backup: $(basename "$BACKUP"))"
  diff "$BACKUP" "$SECRET_FILE" | grep -E '^[<>]' | sed -E 's/^(<|>)[[:space:]]*([A-Z_]+)=.*/\1 \2=.../'
fi

# 2. Prints the configured user, identified by USER_ID, from users.csv (never its password) and its
# catalogs from users-acl.csv.
USER_ID=$(grep -E '^[[:space:]]*USER_ID[[:space:]]*=' "$SECRET_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]')
if [ -z "$USER_ID" ]; then
  echo "USER_ID is not set in $(basename "$SECRET_FILE"): set it to one of the users in $(basename "$USERS_FILE")"
  exit 1
fi
if [ ! -f "$USERS_FILE" ]; then
  echo "Missing $USERS_FILE"
  exit 1
fi

# Fields: username,password,email,slug,firstName,lastName - the first two mandatory.
USER_LINE=$(awk -F',' -v id="$USER_ID" '
  /^[[:space:]]*#/ { next }
  { for (i = 1; i <= NF; i++) gsub(/^[[:space:]]+|[[:space:]]+$/, "", $i) }
  $1 == id { print; exit }
' "$USERS_FILE")
if [ -z "$USER_LINE" ]; then
  echo "USER_ID '$USER_ID' is not one of the users in $(basename "$USERS_FILE")"
  exit 1
fi

field() {
  echo "$USER_LINE" | awk -F',' -v n="$1" '{ v = $n; gsub(/^[[:space:]]+|[[:space:]]+$/, "", v); print v }'
}
PASSWORD_STATUS="set"
if [ -z "$(field 2)" ]; then
  PASSWORD_STATUS="MISSING"
fi
CATALOGS=""
if [ -f "$USERS_ACL_FILE" ]; then
  CATALOGS=$(awk -F',' -v id="$USER_ID" '
    /^[[:space:]]*#/ { next }
    { for (i = 1; i <= NF; i++) gsub(/^[[:space:]]+|[[:space:]]+$/, "", $i) }
    $1 == id { print $2; exit }
  ' "$USERS_ACL_FILE")
fi

echo "Configured user (USER_ID) in $CONFIG_DIR:"
echo "  username:   $USER_ID"
echo "  password:   $PASSWORD_STATUS"
echo "  email:      $(field 3)"
echo "  slug:       $(field 4)"
echo "  first name: $(field 5)"
echo "  last name:  $(field 6)"
echo "  catalogs:   ${CATALOGS:-none (no entry in $(basename "$USERS_ACL_FILE"): all catalogs denied)}"
