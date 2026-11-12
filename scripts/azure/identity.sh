#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure
source $AFON_ROOT/scripts/azure/polaris-cluster-source.sh

# For AZURE_WORKLOAD_IDENTITY=true, where the app registration AZURE_APP_ID has a federated credential instead of a secret, which trusts the
# OIDC issuer of the AKS cluster that serves Polaris for its service account.  Checks that the app has it, which is needed after every build
# of the cluster, whose issuer is new, and if not tells the admin what to run, or, as admin, ie if the az login may change the app
# registration, creates it without asking, like create.sh with create-admin.sh, after showing it.
#
# azure-identity [true|false] [<stack>]
#   true|false  whether to run as admin, by default false
#   <stack>     by its id or its role in the cloud (identity, catalog or tre), by default the one of the stacks that deploy Polaris that
#               runs, found by its AKS cluster <stack>-<CLUSTER_NAME> in AZURE_RESOURCE_GROUP
AS_ADMIN=false
STACK_ARG=
for ARG in "$@"; do
  case "$ARG" in
    true|false) AS_ADMIN="$ARG" ;;
    azure-*|identity|catalog|tre) STACK_ARG="$ARG" ;;
    *) echo "-- Unknown argument '$ARG': expected true|false, or a stack by its id or role"; exit 1 ;;
  esac
done

if [ -z "${AZURE_APP_ID:-}" ]; then
  echo "- AZURE_APP_ID is not set - set it in secret-azure.properties"
  exit 1
fi
azure_polaris_cluster "$AZURE_RESOURCE_GROUP" "$STACK_ARG" || exit 1

ISSUER="$(az aks show --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --query oidcIssuerProfile.issuerUrl -o tsv 2>/dev/null)"
if [ -z "$ISSUER" ]; then
  echo "- Could not get the OIDC issuer of the AKS cluster '$STACK_CLUSTER_NAME' in $AZURE_RESOURCE_GROUP: does it exist, and is the az login allowed to read it?"
  exit 1
fi
SUBJECT="system:serviceaccount:default:polaris"   # AzureWorkloadIdentity.subject()
AUDIENCE="api://AzureADTokenExchange"             # AzureWorkloadIdentity.AUDIENCE

echo "- The federated credential of the app $AZURE_APP_ID for the cluster $STACK_CLUSTER_NAME:"
echo "-- issuer:    $ISSUER"
echo "-- subject:   $SUBJECT"
echo "-- audiences: $AUDIENCE"

if [ "$AS_ADMIN" = "true" ]; then
  echo "- AS_ADMIN=true, running identity-admin.sh"
  # true is the choice to approve, and a script run by the launcher has no terminal to ask in
  set -- approve --resource-group "$AZURE_RESOURCE_GROUP" --cluster "$STACK_CLUSTER_NAME" --app-id "$AZURE_APP_ID" --yes
  source $AFON_ROOT/scripts/azure/identity-admin.sh || exit 1
else
  if MATCHING="$(az ad app federated-credential list --id "$AZURE_APP_ID" \
       --query "[?issuer=='$ISSUER' && subject=='$SUBJECT'].name" -o tsv 2>/dev/null)" && [ -n "$MATCHING" ]; then
    echo "- The app has it: $MATCHING"
  else
    echo "-- The app does not have it, or the az login may not read the app registration.  Ask your admin to approve it, or pass true as the first arg"
    echo "-- if you may change the app registration.  The admin reads the issuer from the cluster, and sees it before it is created:"
    echo "-- with the same config:"
    echo "afon-cli.sh azure-identity-admin approve"
    echo "-- without it:"
    echo "afon-cli.sh azure-identity-admin approve --resource-group $AZURE_RESOURCE_GROUP --cluster $STACK_CLUSTER_NAME --app-id $AZURE_APP_ID"
    exit 1
  fi
fi
