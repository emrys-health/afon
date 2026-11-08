#!/usr/bin/env bash
# Logs in to Azure as the identity that the GitHub environment of the job is trusted for, with a fresh OIDC token of the job, and keeps
# the login fresh.  For the jobs of the smoke tests workflows, after the azure/login action: see docs/github_workflows.md.
#
# azure-login-oidc
#
# The token that the login is made with lasts 5 minutes, and az keeps it to get the tokens of the resources it has not been asked
# for yet, eg the storage of the cdktf store at the end of a run, when it is long expired: AADSTS700024, client assertion not
# within its valid time range.  So the login is made again with a fresh token, as cdktf-store does before it uses the store.
#
# Needs the env variables AZURE_OIDC_CLIENT_ID, AZURE_OIDC_TENANT_ID and AZURE_OIDC_SUBSCRIPTION_ID, and those that GitHub Actions
# sets for the jobs with the permission id-token: write, ACTIONS_ID_TOKEN_REQUEST_URL and ACTIONS_ID_TOKEN_REQUEST_TOKEN.
# Requires az, curl and jq.

azure_oidc_usage(){
  echo "Usage: azure-login-oidc"
}

azure_oidc_login(){
  local MISSING="" VAR JWT
  for VAR in AZURE_OIDC_CLIENT_ID AZURE_OIDC_TENANT_ID AZURE_OIDC_SUBSCRIPTION_ID ACTIONS_ID_TOKEN_REQUEST_URL ACTIONS_ID_TOKEN_REQUEST_TOKEN; do
    [ -n "${!VAR:-}" ] || MISSING="$MISSING $VAR"
  done
  if [ -n "$MISSING" ]; then
    echo "Cannot log in to Azure with the OIDC token of the job, missing env variables:$MISSING" >&2
    return 1
  fi
  JWT=$(curl -fsSL -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=api://AzureADTokenExchange" \
          | jq -er .value) || { echo "Cannot get an OIDC token from GitHub" >&2; return 1; }
  echo "::add-mask::$JWT"
  az login --service-principal -u "$AZURE_OIDC_CLIENT_ID" --tenant "$AZURE_OIDC_TENANT_ID" --federated-token "$JWT" \
    --allow-no-subscriptions --output none || return 1
  az account set --subscription "$AZURE_OIDC_SUBSCRIPTION_ID" || return 1
  echo "Logged in to Azure with a fresh token"
}

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) azure_oidc_usage; exit 0 ;;
    *) echo "Unknown option $1" >&2; azure_oidc_usage >&2; exit 1 ;;
  esac
done

azure_oidc_login || exit 1
