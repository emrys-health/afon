CLOUD=${1:-}
set -a
source $AFON_HOME/config/afon.properties
if [ -n "$CLOUD" ]; then
  source $AFON_HOME/config/afon-$CLOUD.properties
fi
source $AFON_HOME/config/catalogs.properties
if [ -n "$CLOUD" ]; then
  source $AFON_HOME/config/catalogs-$CLOUD.properties
fi
set +a

# OIDC_DOMAIN_NAME is then the OIDC domain name of the cloud, as the config resolves it (AfonPropertyProvider.getOidcDomainName):
# <CLOUD>_OIDC_DOMAIN_NAME, eg AWS_OIDC_DOMAIN_NAME, if it is set and not blank, else the OIDC_DOMAIN_NAME of afon.properties.
if [ -n "$CLOUD" ]; then
  CLOUD_OIDC_DOMAIN_NAME_PROPERTY="$(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')_OIDC_DOMAIN_NAME"
  CLOUD_OIDC_DOMAIN_NAME="${!CLOUD_OIDC_DOMAIN_NAME_PROPERTY:-}"
  if [ -n "${CLOUD_OIDC_DOMAIN_NAME//[[:space:]]/}" ]; then
    export OIDC_DOMAIN_NAME="$CLOUD_OIDC_DOMAIN_NAME"
  fi
  unset CLOUD_OIDC_DOMAIN_NAME_PROPERTY CLOUD_OIDC_DOMAIN_NAME
fi
