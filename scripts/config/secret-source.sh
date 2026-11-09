#!/usr/bin/env bash
CLOUD=${1:-}

# Prints the names that a properties file sets
property_names() {
  sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$1"
}

# Saves the values of the names that a properties file sets, which are already set, as SAVED_<name>
save_set_values() {
  local NAME
  for NAME in $(property_names "$1"); do
    if [ -n "${!NAME+x}" ]; then
      printf -v "SAVED_$NAME" '%s' "${!NAME}"
    fi
  done
}

# Restores the values saved by save_set_values, and exports them
restore_saved_values() {
  local NAME SAVED
  for NAME in $(property_names "$1"); do
    SAVED="SAVED_$NAME"
    if [ -n "${!SAVED+x}" ]; then
      export "$NAME=${!SAVED}"
      unset "$SAVED"
    fi
  done
}

# afon.properties first: the secrets may use its names, eg ENV_NAME in KEYCLOAK_DB_URL.  The names that are already set keep their
# value, as afon-source.sh may have overridden them, eg OIDC_DOMAIN_NAME with that of the cloud.
AFON_PROPERTIES="$AFON_HOME/config/afon.properties"
save_set_values "$AFON_PROPERTIES"
set -a
source "$AFON_PROPERTIES"
source $AFON_HOME/config/secret.properties
if [ -n "$CLOUD" ] && [ "$CLOUD" != "ALL" ]; then
  source $AFON_HOME/config/secret-$CLOUD.properties
fi
set +a
restore_saved_values "$AFON_PROPERTIES"
