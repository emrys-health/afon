source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

usage(){
  echo "Usage: $0 <issuer>"
  echo "Issue and stores in Azure Key Vault the cert for the $AZURE_POLARIS_DOMAIN_NAME."
  echo "The supported issuers are:"
  echo " - acme via Let's Encrypt (free)"
  echo " - digicert|globalsign via Key Vault CA (paying service)"
}

ISSUER="${1:-}"

ACME_HOME="${ACME_HOME:-$AFON_HOME/acme}"

require_key_vault() {
  if [ -z "$AZURE_KEY_VAULT_NAME" ]; then
    echo "- AZURE_KEY_VAULT_NAME is blank - set it in afon-azure.properties before caching a cert"
    exit 1
  fi
  if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
    :
  else
    echo "- Key Vault $AZURE_KEY_VAULT_NAME does not exist, creating"
    "$AFON_ROOT/scripts/azure/keyvault.sh" create || exit 1
  fi
}

# Stores a PEM cert + key pair under the secret names AzurePolarisStack's restore step reads
# from. Common tail end for every issuer below, so the CDK side stays issuer-agnostic.
store_cert() {
  CERT_PEM="$1"
  KEY_PEM="$2"
  KEBAB_DOMAIN_NAME="${AZURE_POLARIS_DOMAIN_NAME//./-}"
  AZURE_TLS_CRT_NAME="${KEBAB_DOMAIN_NAME}-tls-crt"
  AZURE_TLS_KEY_NAME="${KEBAB_DOMAIN_NAME}-tls-key"

  echo "- Caching $AZURE_POLARIS_DOMAIN_NAME cert in Key Vault $AZURE_KEY_VAULT_NAME"
  az keyvault secret set --vault-name $AZURE_KEY_VAULT_NAME --name "$AZURE_TLS_CRT_NAME" \
    --value "$(base64 < "$CERT_PEM" | tr -d '\n')" -o none || exit 1
  az keyvault secret set --vault-name $AZURE_KEY_VAULT_NAME --name "$AZURE_TLS_KEY_NAME" \
    --value "$(base64 < "$KEY_PEM" | tr -d '\n')" -o none || exit 1

  echo "Cert secrets $AZURE_TLS_CRT_NAME and $AZURE_TLS_KEY_NAME cached in $AZURE_KEY_VAULT_NAME"
}

# Fetches the acme.sh script directly rather than running its install.sh - we invoke it
# by explicit path with --config-home every time, so install.sh's cron job and shell
# profile setup (and its own argument parsing, which chokes on --home in some versions)
# aren't needed.
install_acme() {
  if [ ! -x "$ACME_HOME/acme.sh" ]; then
    echo "- Fetching acme.sh to $ACME_HOME"
    mkdir -p "$ACME_HOME"
    FETCH_DIR="$(mktemp -d)"
    curl -sL https://github.com/acmesh-official/acme.sh/archive/master.tar.gz | tar xz -C "$FETCH_DIR" || exit 1
    cp -R "$FETCH_DIR"/acme.sh-master/. "$ACME_HOME/" || exit 1
    chmod +x "$ACME_HOME/acme.sh"
    rm -rf "$FETCH_DIR"
  fi
}

# Issues (or renews, idempotently) the domain's cert directly against Let's Encrypt via DNS01.
# Free, but subject to letsencrypt's 90-day validity and usage rate limits.
issue_via_acme() {
  install_acme

  # DNS Zone role, needed by acme.sh's dns_azure hook to create/delete the
  # _acme-challenge TXT record on our behalf.
  DNS_ZONE_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Network/dnsZones/$AZURE_POLARIS_DOMAIN_NAME"
  if [ -n "$(az role assignment list --assignee $AZURE_APP_ID --role "DNS Zone Contributor" --scope "$DNS_ZONE_SCOPE" --query "[].id" -o tsv)" ]; then
    echo "- Role DNS Zone Contributor already assigned on $AZURE_POLARIS_DOMAIN_NAME, skipping"
  else
    echo "- Granting DNS Zone Contributor on $AZURE_POLARIS_DOMAIN_NAME to $AZURE_APP_ID"
    az role assignment create  --role "DNS Zone Contributor" --assignee $AZURE_APP_ID --scope "$DNS_ZONE_SCOPE" || exit 1
  fi

  # acme.sh's dns_azure hook reads these specific variable names to authenticate as the service
  # principal above when it creates/deletes the _acme-challenge TXT record.
  export AZUREDNS_SUBSCRIPTIONID=$AZURE_SUBSCRIPTION
  export AZUREDNS_TENANTID=$AZURE_TENANT_ID
  export AZUREDNS_APPID=$AZURE_APP_ID
  export AZUREDNS_CLIENTSECRET=$AZURE_APP_SECRET

  echo "- Requesting/renewing $AZURE_POLARIS_DOMAIN_NAME via Let's Encrypt (DNS-01)"
  "$ACME_HOME/acme.sh" --config-home "$ACME_HOME" --issue --dns dns_azure \
    -d "$AZURE_POLARIS_DOMAIN_NAME" \
    --keylength 2048 \
    --server letsencrypt \
    --accountemail "admin@$AZURE_POLARIS_DOMAIN_NAME"
  ACME_EXIT=$?
  # acme.sh exits 2 for "not yet due for renewal" - not an error, the cached cert is
  # still valid and gets re-pushed below unchanged.
  if [ $ACME_EXIT -ne 0 ] && [ $ACME_EXIT -ne 2 ]; then
    echo "- acme.sh issue failed (exit $ACME_EXIT)"
    exit 1
  fi

  CERT_DIR="$ACME_HOME/$AZURE_POLARIS_DOMAIN_NAME"
  store_cert "$CERT_DIR/fullchain.cer" "$CERT_DIR/$AZURE_POLARIS_DOMAIN_NAME.key"
}

# Issues via Key Vault's own integrated CA (DigiCert or GlobalSign)
# Key Vault handles renewal on its own schedule going forward.
# Needs an account already set up with that CA issuer;
# AZURE_CA_ID/AZURE_CA_PASSWORD (in config/secret-azure.properties) are that account's credentials,
# used once to register the issuer in Key Vault.
issue_via_keyvault_ca() {
  PROVIDER="$1"
  ISSUER_NAME="afon-$(echo "$PROVIDER" | tr '[:upper:]' '[:lower:]')-issuer"
  CERT_NAME="${AZURE_POLARIS_DOMAIN_NAME//./-}"

  if [ -z "$AZURE_CA_ID" ] || [ -z "$AZURE_CA_PASSWORD" ]; then
    echo "- AZURE_CA_ID/AZURE_CA_PASSWORD are blank - set them in secret-azure.properties (from your $PROVIDER account) first"
    exit 1
  fi

  if az keyvault certificate issuer show --vault-name $AZURE_KEY_VAULT_NAME --issuer-name "$ISSUER_NAME" -o none 2>/dev/null; then
    echo "- Issuer $ISSUER_NAME already registered, skipping"
  else
    echo "- Registering $PROVIDER as issuer $ISSUER_NAME in Key Vault"
    az keyvault certificate issuer create --vault-name $AZURE_KEY_VAULT_NAME --issuer-name "$ISSUER_NAME" \
      --provider-name "$PROVIDER" --account-id "$AZURE_CA_ID" --password "$AZURE_CA_PASSWORD" || exit 1
  fi

  POLICY=$(cat <<EOF
{
  "issuerParameters": { "name": "$ISSUER_NAME" },
  "keyProperties": { "exportable": true, "keyType": "RSA", "keySize": 2048, "reuseKey": false },
  "secretProperties": { "contentType": "application/x-pkcs12" },
  "x509CertificateProperties": { "subject": "CN=$AZURE_POLARIS_DOMAIN_NAME", "validityInMonths": 12 }
}
EOF
)
  echo "- Requesting $PROVIDER cert for $AZURE_POLARIS_DOMAIN_NAME via Key Vault"
  az keyvault certificate create --vault-name $AZURE_KEY_VAULT_NAME --name "$CERT_NAME" --policy "$POLICY" || exit 1

  echo "- Waiting for issuance to complete"
  STATUS=""
  for i in $(seq 1 30); do
    STATUS=$(az keyvault certificate pending show --vault-name $AZURE_KEY_VAULT_NAME --name "$CERT_NAME" --query status -o tsv 2>/dev/null)
    [ "$STATUS" = "completed" ] && break
    sleep 10
  done
  if [ "$STATUS" != "completed" ]; then
    echo "- Certificate issuance did not complete in time (last status: $STATUS)"
    exit 1
  fi

  TMP="$(mktemp -d)"
  az keyvault secret show --vault-name $AZURE_KEY_VAULT_NAME --name "$CERT_NAME" --query value -o tsv \
    | base64 -d > "$TMP/cert.pfx"
  openssl pkcs12 -in "$TMP/cert.pfx" -clcerts -nokeys -passin pass: -out "$TMP/cert.pem" || exit 1
  openssl pkcs12 -in "$TMP/cert.pfx" -nocerts -nodes -passin pass: -out "$TMP/key.pem" || exit 1

  store_cert "$TMP/cert.pem" "$TMP/key.pem"
  rm -rf "$TMP"
}

require_key_vault

case "$ISSUER" in
  acme)
    issue_via_acme
    ;;

  digicert)
    issue_via_keyvault_ca "DigiCert"
    ;;

  globalsign)
    issue_via_keyvault_ca "GlobalSign"
    ;;

  *)
    usage
    exit 1
    ;;
esac
