source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <issuer>"
  echo "Issue and stores in Azure Key Vault the cert of each domain name that is configured, ie not blank, of"
  echo "AZURE_POLARIS_DOMAIN_NAME, AZURE_JUPYTER_DOMAIN_NAME and OIDC_DOMAIN_NAME."
  echo "The supported issuers are:"
  echo " - acme via Let's Encrypt (free - default)"
  echo " - digicert|globalsign via Key Vault CA (paying service)"
}

ISSUER="${1:-acme}"

ACME_HOME="${ACME_HOME:-$AFON_HOME/acme}"
# The release of acme.sh fetched, rather than its master branch, which can be broken at any commit
ACME_VERSION="${ACME_VERSION:-3.1.6}"

# The domain names to issue the certs of, those configured, without duplicates
DOMAIN_NAMES=""
for DOMAIN_PROPERTY in AZURE_POLARIS_DOMAIN_NAME AZURE_JUPYTER_DOMAIN_NAME OIDC_DOMAIN_NAME; do
  DOMAIN_NAME="${!DOMAIN_PROPERTY:-}"
  if [ -z "$DOMAIN_NAME" ]; then
    echo "- $DOMAIN_PROPERTY is not set, skipping its cert"
  else
    case " $DOMAIN_NAMES " in
      *" $DOMAIN_NAME "*) ;;
      *) DOMAIN_NAMES="$DOMAIN_NAMES $DOMAIN_NAME" ;;
    esac
  fi
done
if [ -z "$DOMAIN_NAMES" ]; then
  echo "- No domain name is configured, nothing to issue"
  exit 1
fi

require_key_vault() {
  if [ -z "$AZURE_KEY_VAULT_NAME" ]; then
    echo "- AZURE_KEY_VAULT_NAME is blank - set it in afon-azure.properties before caching a cert"
    exit 1
  fi
  if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
    echo "- Using Key Vault $AZURE_KEY_VAULT_NAME"
  else
    echo "- Key Vault $AZURE_KEY_VAULT_NAME does not exist, creating"
    # AFON_ROOT is a plain shell variable in the CLI, so pass it explicitly to the child script
    AFON_ROOT="$AFON_ROOT" "$AFON_ROOT/scripts/azure/keyvault.sh" create || exit 1
  fi
}

# Retries `az role assignment create` for up to two minutes. A just-activated PIM role (or a
# just-updated ABAC condition) can take a short while to propagate to Microsoft.Authorization
# itself, so the create call can fail with AuthorizationFailed even though the grant is otherwise
# correct - failing outright here would abort the whole script before store_cert (or whatever
# needed the grant) ever runs, silently leaving the cert issued locally but never cached in the
# vault. See roles.md for the tvs incident this was written for.
create_role_assignment_with_retry() {
  ROLE="$1" OBJECT_ID="$2" PRINCIPAL_TYPE="$3" SCOPE="$4"
  for i in $(seq 1 12); do
    az role assignment create --role "$ROLE" --assignee-object-id $OBJECT_ID \
      --assignee-principal-type "$PRINCIPAL_TYPE" --scope "$SCOPE" -o none && return
    [ "$i" -eq 12 ] && { echo "- Failed to grant $ROLE after repeated retries"; exit 1; }
    echo "- Grant not accepted yet (propagation?), retrying in 10s..."
    sleep 10
  done
}

# Grants the given Key Vault role to the signed-in principal, at vault scope, if not already
# held, then waits for it to propagate to the data plane before returning - a just-created role
# assignment can take a short while to reach Key Vault, so an immediate data-plane call can 403
# even though the assignment exists. Requested here rather than at vault-creation time
# (keyvault.sh), since which role is actually needed depends on the issuer - acme never needs
# Key Vault Certificates Officer.
grant_vault_role() {
  ROLE="$1"
  PRINCIPAL_ID="$(az ad signed-in-user show --query id -o tsv)"
  VAULT_SCOPE="$(az keyvault show --name $AZURE_KEY_VAULT_NAME --query id -o tsv)"
  if [ -n "$(az role assignment list --assignee-object-id $PRINCIPAL_ID --role "$ROLE" --scope "$VAULT_SCOPE" --query "[].id" -o tsv)" ]; then
    echo "- Role $ROLE already assigned on $AZURE_KEY_VAULT_NAME, skipping"
    return
  fi

  echo "- Granting $ROLE on $AZURE_KEY_VAULT_NAME to $PRINCIPAL_ID"
  create_role_assignment_with_retry "$ROLE" "$PRINCIPAL_ID" "User" "$VAULT_SCOPE"

  # Probe with an operation actually covered by the role just granted - Secrets Officer and
  # Certificates Officer cover disjoint data actions, so a single hardcoded probe would give a
  # false negative for whichever role it doesn't match (and spin the full retry for nothing).
  case "$ROLE" in
    *Certificates*) PROBE_ARGS="certificate list --vault-name $AZURE_KEY_VAULT_NAME" ;;
    *)              PROBE_ARGS="secret list --vault-name $AZURE_KEY_VAULT_NAME" ;;
  esac

  echo "- Waiting for the grant to propagate..."
  for i in $(seq 1 12); do
    az keyvault $PROBE_ARGS -o none 2>/dev/null && return
    [ "$i" -eq 12 ] && { echo "- Grant did not propagate in time, re-run issue-cert.sh"; exit 1; }
    sleep 10
  done
}

# Stores a PEM cert + key pair of the domain name under the secret names the restore step of the stack reads
# from. Common tail end for every issuer below, so the CDK side stays issuer-agnostic.
# Requires role "Key Vault Secrets Officer"
# $1: domain name   $2: cert PEM file   $3: key PEM file
store_cert() {
  DOMAIN_NAME="$1"
  CERT_PEM="$2"
  KEY_PEM="$3"
  KEBAB_DOMAIN_NAME="${DOMAIN_NAME//./-}"
  AZURE_TLS_CRT_NAME="${KEBAB_DOMAIN_NAME}-tls-crt"
  AZURE_TLS_KEY_NAME="${KEBAB_DOMAIN_NAME}-tls-key"

  grant_vault_role "Key Vault Secrets Officer"

  echo "- Caching $DOMAIN_NAME cert in Key Vault $AZURE_KEY_VAULT_NAME"
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
    curl -sfL "https://github.com/acmesh-official/acme.sh/archive/refs/tags/$ACME_VERSION.tar.gz" | tar xz -C "$FETCH_DIR" || exit 1
    cp -R "$FETCH_DIR/acme.sh-$ACME_VERSION/." "$ACME_HOME/" || exit 1
    chmod +x "$ACME_HOME/acme.sh"
    rm -rf "$FETCH_DIR"
  fi
}

# Needed only for acme's DNS-01 challenge: acme.sh's dns_azure hook creates/deletes the
# _acme-challenge TXT record in the DNS zone to prove domain ownership to Let's Encrypt. The
# digicert/globalsign path never touches Azure DNS - validation happens through the CA's own
# integration with Key Vault instead - so it never needs this role. Granted to the app's service
# principal object ID (AZURE_SP_ID), not AZURE_APP_ID (the application/client ID, a different
# GUID) - AZUREDNS_APPID below is a separate concern, that's how acme.sh authenticates.
# $1: domain name
grant_dns_zone_role() {
  DOMAIN_NAME="$1"
  DNS_ZONE_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Network/dnsZones/$DOMAIN_NAME"
  GRANTED_ON="$(az role assignment list --assignee-object-id $AZURE_SP_ID --role "DNS Zone Contributor" --scope "$DNS_ZONE_SCOPE" --query "[0].createdOn" -o tsv)"
  if [ -n "$GRANTED_ON" ]; then
    echo "- Role DNS Zone Contributor already assigned on $DOMAIN_NAME, skipping"
    # A recent grant may still be propagating, which takes up to ten minutes or more, eg after a first attempt failed
    GRANT_AGE="$(python3 -c "import sys,datetime as d; print(int((d.datetime.now(d.timezone.utc)-d.datetime.fromisoformat(sys.argv[1])).total_seconds()))" "$GRANTED_ON" 2>/dev/null || echo 99999)"
    if [ "$GRANT_AGE" -lt 1200 ]; then
      DNS_ZONE_ROLE_GRANTED=true
    fi
  else
    echo "- Granting DNS Zone Contributor on $DOMAIN_NAME to $AZURE_SP_ID"
    create_role_assignment_with_retry "DNS Zone Contributor" "$AZURE_SP_ID" "ServicePrincipal" "$DNS_ZONE_SCOPE"
    DNS_ZONE_ROLE_GRANTED=true
  fi
}

# Waits, for up to fifteen minutes, until the service principal acme.sh signs in as can write a TXT record in the zone of
# the domain name, ie until its DNS Zone Contributor grant has propagated, by adding and deleting a probe record.  It signs
# in with a login of its own, so as not to change that of the deployer, and fails with the error of Azure otherwise.
# $1: domain name
wait_for_dns_zone_access() {
  DOMAIN_NAME="$1"
  echo "- Waiting for the DNS Zone Contributor grant on $DOMAIN_NAME to propagate"
  (
    PROBE_DIR="$(mktemp -d)"
    export AZURE_CONFIG_DIR="$PROBE_DIR"
    trap 'rm -rf "$PROBE_DIR"' EXIT
    az login --service-principal -u "$AZURE_APP_ID" -p "$AZURE_APP_SECRET" --tenant "$AZURE_TENANT_ID" -o none || exit 1
    PROBE_ARGS="--subscription $AZURE_SUBSCRIPTION --resource-group $AZURE_RESOURCE_GROUP --zone-name $DOMAIN_NAME --record-set-name _afon-access-probe"
    ERROR=""
    for ATTEMPT in $(seq 1 30); do
      if ERROR="$(az network dns record-set txt add-record $PROBE_ARGS --value probe -o none 2>&1)"; then
        az network dns record-set txt delete $PROBE_ARGS --yes -o none
        echo "- The service principal may write to the zone of $DOMAIN_NAME"
        exit 0
      fi
      echo "- Not yet ($ATTEMPT/30), retrying in 30s..."
      sleep 30
    done
    echo "- The service principal still may not write to the zone of $DOMAIN_NAME:"
    echo "$ERROR"
    exit 1
  ) || exit 1
}

# Issues (or renews, idempotently) the domain's cert directly against Let's Encrypt via DNS01.
# Free, but subject to letsencrypt's 90-day validity and usage rate limits.
# $1: domain name, whose DNS zone must be in the resource group, as the challenge is a record in it
issue_via_acme() {
  DOMAIN_NAME="$1"
  install_acme
  DNS_ZONE_ROLE_GRANTED=false
  grant_dns_zone_role "$DOMAIN_NAME"

  # acme.sh's dns_azure hook reads these specific variable names to authenticate as the service
  # principal above when it creates/deletes the _acme-challenge TXT record.
  export AZUREDNS_SUBSCRIPTIONID=$AZURE_SUBSCRIPTION
  export AZUREDNS_TENANTID=$AZURE_TENANT_ID
  export AZUREDNS_APPID=$AZURE_APP_ID
  export AZUREDNS_CLIENTSECRET=$AZURE_APP_SECRET

  # A role just granted can take some minutes to propagate, during which acme.sh cannot add its TXT record.  Every
  # attempt failing counts toward Let's Encrypt's limit of 5 failed authorizations per domain name per hour, so acme.sh
  # is never retried: Azure DNS is probed as the service principal instead, until it may write to the zone.
  if [ "$DNS_ZONE_ROLE_GRANTED" = "true" ]; then
    wait_for_dns_zone_access "$DOMAIN_NAME"
  fi

  echo "- Requesting/renewing $DOMAIN_NAME via Let's Encrypt (DNS-01)"
  # The CLI runs scripts under set -e, so capture the exit code without aborting on it
  ACME_EXIT=0
  "$ACME_HOME/acme.sh" --config-home "$ACME_HOME" --issue --dns dns_azure \
    -d "$DOMAIN_NAME" \
    --keylength 2048 \
    --server letsencrypt \
    --accountemail "admin@$DOMAIN_NAME" || ACME_EXIT=$?
  # acme.sh exits 2 for "not yet due for renewal" - not an error, the cached cert is
  # still valid and gets re-pushed below unchanged.
  if [ $ACME_EXIT -ne 0 ] && [ $ACME_EXIT -ne 2 ]; then
    echo "- acme.sh issue failed (exit $ACME_EXIT)"
    exit 1
  fi

  CERT_DIR="$ACME_HOME/$DOMAIN_NAME"
  store_cert "$DOMAIN_NAME" "$CERT_DIR/fullchain.cer" "$CERT_DIR/$DOMAIN_NAME.key"
}

# Issues via Key Vault's own integrated CA (DigiCert or GlobalSign)
# Key Vault handles renewal on its own schedule going forward.
# Needs an account already set up with that CA issuer;
# AZURE_CA_ID/AZURE_CA_PASSWORD (in config/secret-azure.properties) are that account's credentials,
# used once to register the issuer in Key Vault.
# $1: provider   $2: domain name
issue_via_keyvault_ca() {
  PROVIDER="$1"
  DOMAIN_NAME="$2"
  ISSUER_NAME="afon-$(echo "$PROVIDER" | tr '[:upper:]' '[:lower:]')-issuer"
  CERT_NAME="${DOMAIN_NAME//./-}"

  if [ -z "$AZURE_CA_ID" ] || [ -z "$AZURE_CA_PASSWORD" ]; then
    echo "- AZURE_CA_ID/AZURE_CA_PASSWORD are blank - set them in secret-azure.properties (from your $PROVIDER account) first"
    exit 1
  fi

  grant_vault_role "Key Vault Certificates Officer"

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
  "x509CertificateProperties": { "subject": "CN=$DOMAIN_NAME", "validityInMonths": 12 }
}
EOF
)
  echo "- Requesting $PROVIDER cert for $DOMAIN_NAME via Key Vault"
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

  store_cert "$DOMAIN_NAME" "$TMP/cert.pem" "$TMP/key.pem"
  rm -rf "$TMP"
}

case "$ISSUER" in
  acme|digicert|globalsign) ;;
  *)
    usage
    exit 1
    ;;
esac

require_key_vault

for DOMAIN in $DOMAIN_NAMES; do
  echo "- Issuing the cert of $DOMAIN via $ISSUER"
  case "$ISSUER" in
    acme)       issue_via_acme "$DOMAIN" ;;
    digicert)   issue_via_keyvault_ca "DigiCert" "$DOMAIN" ;;
    globalsign) issue_via_keyvault_ca "GlobalSign" "$DOMAIN" ;;
  esac
done
