#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

# Args, in any order: whether to run as admin, true|false, and the stack, given as its id, or its role in the cloud (identity,
# catalog or tre), whose AKS cluster the Polaris pod runs in.  By default not as admin.
# For AZURE_WORKLOAD_IDENTITY=true, where the app registration AZURE_APP_ID has a federated credential instead of a secret, which
# trusts the OIDC issuer of that cluster for the service account of the Polaris pod.  It reports whether the app has it, and if not,
# creates it as admin, ie if the az login may change the app registration, like create.sh with create-admin.sh, else prints what the
# admin runs.  The issuer is that of the cluster, so the credential is needed again if the cluster is rebuilt, which must exist.
AS_ADMIN=false
STACK_ARG=
for ARG in "$@"; do
  case "$ARG" in
    true|false) AS_ADMIN="$ARG" ;;
    azure-*|identity|catalog|tre) STACK_ARG="$ARG" ;;
    *) echo "-- Unknown argument '$ARG': expected true|false, or a stack by its id or role"; exit 1 ;;
  esac
done
if [ -z "$STACK_ARG" ]; then
  echo "-- Requires the stack, by its id or role: azure-identity [true|false] <stack-id|identity|catalog|tre>"
  exit 1
fi
source $AFON_ROOT/scripts/azure/stack-source.sh "$STACK_ARG"

if [ -z "${AZURE_APP_ID:-}" ]; then
  echo "- AZURE_APP_ID is not set - set it in secret-azure.properties"
  exit 1
fi

ISSUER="$(az aks show --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --query oidcIssuerProfile.issuerUrl -o tsv 2>/dev/null)"
if [ -z "$ISSUER" ]; then
  echo "- Could not get the OIDC issuer of the AKS cluster '$STACK_CLUSTER_NAME' in $AZURE_RESOURCE_GROUP: does it exist, and is the az login allowed to read it?"
  exit 1
fi
SUBJECT="system:serviceaccount:default:polaris"   # AzureWorkloadIdentity.subject()
AUDIENCE="api://AzureADTokenExchange"             # AzureWorkloadIdentity.AUDIENCE
NAME="afon-$STACK_CLUSTER_NAME"

echo "- The federated credential of the app $AZURE_APP_ID for the cluster $STACK_CLUSTER_NAME:"
echo "-- issuer:    $ISSUER"
echo "-- subject:   $SUBJECT"
echo "-- audiences: $AUDIENCE"

if [ "$AS_ADMIN" = "true" ]; then
  echo "- AS_ADMIN=true, running identity-admin.sh"
  set -- "$NAME" "$ISSUER" "$SUBJECT" "$AZURE_APP_ID"
  source $AFON_ROOT/scripts/azure/identity-admin.sh || exit 1
else
  if MATCHING="$(az ad app federated-credential list --id "$AZURE_APP_ID" \
       --query "[?issuer=='$ISSUER' && subject=='$SUBJECT'].name" -o tsv 2>/dev/null)" && [ -n "$MATCHING" ]; then
    echo "- The app has it: $MATCHING"
  else
    echo "-- The app does not have it, or the az login may not read the app registration."
    echo "-- Ask your admin to run (or pass true as the first arg if you may change the app registration):"
    echo "afon-cli.sh azure-identity-admin $NAME '$ISSUER' '$SUBJECT' $AZURE_APP_ID"
    exit 1
  fi
fi
