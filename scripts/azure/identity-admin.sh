#!/usr/bin/env bash
# Admin part of azure-identity, like create-admin.sh is of create.sh: needs the rights to change the app registration, eg as its
# owner, or Application Administrator, and nothing from the config or the cluster, so the admin can run it from what the deployer
# hands over.  Gives the app registration a federated credential, which lets a token of the issuer for the subject act as the app
# instead of its secret, see AZURE_WORKLOAD_IDENTITY.  Idempotent: a credential of the same issuer and subject is left as it is.
#
# azure-identity-admin <name> <issuer> <subject> [<app-id>]
#   <name>     of the credential, eg afon-deployer, or afon-<stack>-<cluster> for a cluster
#   <issuer>   of the tokens: for the pods of an AKS cluster the issuer of the cluster, which azure-identity prints, for the deploys
#              of an Azure DevOps Services pipeline the issuer that its service connection shows
#   <subject>  of the tokens: for the Polaris pod system:serviceaccount:default:polaris, for a service connection of Azure DevOps
#              sc://<organization>/<project>/<service connection name>
#   <app-id>   the client ID of the app registration, by default APP_ID or AZURE_APP_ID
FED_NAME="${1:-}"
FED_ISSUER="${2:-}"
FED_SUBJECT="${3:-}"
FED_APP_ID="${4:-${APP_ID:-${AZURE_APP_ID:-}}}"
FED_AUDIENCE="api://AzureADTokenExchange"

if [ -z "$FED_NAME" ] || [ -z "$FED_ISSUER" ] || [ -z "$FED_SUBJECT" ] || [ -z "$FED_APP_ID" ]; then
  echo "Usage: azure-identity-admin <name> <issuer> <subject> [<app-id>]"
  echo "-- <app-id> is by default APP_ID or AZURE_APP_ID"
  exit 1
fi

echo "- Giving the app '$FED_APP_ID' a federated credential '$FED_NAME'"
FED_EXISTING="$(az ad app federated-credential list --id "$FED_APP_ID" \
  --query "[?issuer=='$FED_ISSUER' && subject=='$FED_SUBJECT'].name" -o tsv)" || {
  echo "-- Could not list the federated credentials of the app: is the az login allowed to read it?"
  exit 1
}
if [ -n "$FED_EXISTING" ]; then
  echo "- The app already has one of this issuer and subject, skipping: $FED_EXISTING"
else
  az ad app federated-credential create --id "$FED_APP_ID" --parameters \
    "{\"name\":\"$FED_NAME\",\"issuer\":\"$FED_ISSUER\",\"subject\":\"$FED_SUBJECT\",\"audiences\":[\"$FED_AUDIENCE\"]}" -o none || exit 1
  echo "- Created. It may take a few minutes to take effect"
fi
