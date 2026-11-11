#!/usr/bin/env bash
# Logs in to Azure as the app registration AZURE_APP_ID: with its secret, or, if AZURE_WORKLOAD_IDENTITY=true, with the token in the
# file AZURE_FEDERATED_TOKEN_FILE, which a federated credential of the app must trust, ie its issuer and subject must be those of the
# token.  Whatever provides the token writes the file, eg a pipeline step, or the projected token of a pod or an agent.
# The token lasts minutes, so log in again with a fresh one if a long run fails with AADSTS700024, see azure-login-oidc.
source $AFON_ROOT/scripts/config/secret-source.sh azure

if [ "${AZURE_WORKLOAD_IDENTITY:-false}" = "true" ]; then
  if [ -z "${AZURE_FEDERATED_TOKEN_FILE:-}" ] || [ ! -s "$AZURE_FEDERATED_TOKEN_FILE" ]; then
    echo "-- AZURE_WORKLOAD_IDENTITY=true: the app has a federated credential, not a secret, so it logs in with a token."
    echo "-- Set AZURE_FEDERATED_TOKEN_FILE to a file that has a token (a JWT) of the issuer and subject that the credential trusts"
    exit 1
  fi
  echo "az login as service principal '$AZURE_APP_ID' for tenant '$AZURE_TENANT_ID' with the federated token in $AZURE_FEDERATED_TOKEN_FILE"
  az login --service-principal -u $AZURE_APP_ID --tenant $AZURE_TENANT_ID --federated-token "$(cat "$AZURE_FEDERATED_TOKEN_FILE")"
else
  if [ -z "${AZURE_APP_SECRET:-}" ]; then
    echo "-- AZURE_APP_SECRET is not set: set it, or set AZURE_WORKLOAD_IDENTITY=true if the app has a federated credential instead"
    exit 1
  fi
  echo "az login as service principal '$AZURE_APP_ID' for tenant '$AZURE_TENANT_ID'"
  az login --service-principal -u $AZURE_APP_ID -p $AZURE_APP_SECRET --tenant $AZURE_TENANT_ID
fi
