#!/usr/bin/env bash
# Admin part of azure-identity, like create-admin.sh is of create.sh: needs the rights to change the app registration, eg as its
# with the app registration role, or Application Administrator.  Gives the app registration a federated credential, which lets a token of the issuer for the
# subject act as the app instead of its secret, see AZURE_WORKLOAD_IDENTITY.  Idempotent: a credential of the same issuer and subject is
# left as it is.
#
# azure-identity-admin approve [<stack>] [-y] [options]
#   Approves the credential of the Polaris pod of the AKS cluster that serves Polaris.  The issuer is not taken from the deployer but
#   read from the cluster itself, so it is the cluster's, and the subject is that of the service account of Polaris,
#   system:serviceaccount:default:polaris.  Shows them and asks for a yes before it creates the credential, unless -y.
# azure-identity-admin create <name> <issuer> <subject> [options]
#   Creates any credential, from the issuer and subject as they are given, eg of the deploys of an Azure DevOps Services pipeline, whose
#   service connection shows the issuer and the subject sc://<organization>/<project>/<service connection name>.
#
# azure-identity-admin grant [--deployer <deployer>] [options]
#   Grants the deployer the app registration role, which lets the deploy give the app the federated credential of each new cluster without going
#   back to the admin, see AZURE_WORKLOAD_IDENTITY.  The deployer can then change that app registration, and no other.  It is, in Entra ID, what
#   az ad app owner add gives.  The deployer is by default the identity that is logged in to az:
#   run it as the deployer's own login if that is also allowed to grant the role, else give --deployer: a user, by its user principal name,
#   eg someone@example.com, or a service principal, by its client ID, or either by its object ID.
#   A service principal also needs the Microsoft Graph application permission Application.ReadWrite.OwnedBy, with the consent of an admin, which this
#   does not give.  Idempotent.
#
# azure-identity-admin ungrant [--deployer <deployer>] [--force] [options]
#   Takes the app registration role back from the deployer, the reverse of grant, with the same default deployer.  Used to test that the deploy
#   fails fast without the role, and to retire a deployer.  It refuses, unless --force, to take it from the identity that is logged in, which then
#   loses the rights to change the app, or from the last one that has it, as then only a tenant admin can give it back.  Idempotent.
#
# The defaults are read from the config of Afon, if there is one, and each can be given instead, so that an admin needs no config:
#   --resource-group <name>  of the cluster, by default AZURE_RESOURCE_GROUP
#   --cluster <name>         of the AKS cluster, by default that of <stack>, else of the running stack that deploys Polaris, ie
#                            <stack>-CLUSTER_NAME as found by the AKS clusters in the resource group
#   --app-id <client id>     of the app registration, by default AZURE_APP_ID, or APP_ID
#   -y, --yes                approve without asking
FED_AUDIENCE="api://AzureADTokenExchange"
FED_POLARIS_SUBJECT="system:serviceaccount:default:polaris"   # AzureWorkloadIdentity.subject()

fed_usage() {
  echo "Usage: azure-identity-admin approve [<stack>] [-y] [--resource-group <name>] [--cluster <name>] [--app-id <client id>]"
  echo "       azure-identity-admin create <name> <issuer> <subject> [--app-id <client id>]"
  echo "       azure-identity-admin grant [--deployer <deployer>] [--app-id <client id>]"
  echo "       azure-identity-admin ungrant [--deployer <deployer>] [--force] [--app-id <client id>]"
  echo "       <deployer> can be specified as user principal name, client id or object id"
}

FED_RG=""
FED_CLUSTER=""
FED_APP_ID=""
FED_DEPLOYER=""
FED_YES=false
FED_FORCE=false
FED_POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --resource-group|-g) FED_RG="${2:?-- $1 needs a value}"; shift 2 ;;
    --cluster)           FED_CLUSTER="${2:?-- $1 needs a value}"; shift 2 ;;
    --app-id)            FED_APP_ID="${2:?-- $1 needs a value}"; shift 2 ;;
    --deployer)          FED_DEPLOYER="${2:?-- $1 needs a value}"; shift 2 ;;
    -y|--yes)            FED_YES=true; shift ;;
    --force)             FED_FORCE=true; shift ;;
    -h|--help)           fed_usage; exit 0 ;;
    -*)                  echo "-- Unknown option '$1'"; fed_usage; exit 1 ;;
    *)                   FED_POS+=("$1"); shift ;;
  esac
done
# the command is explicit: approve, create, grant or ungrant
FED_ACTION=""
case "${FED_POS[0]:-}" in
  approve|create|grant|ungrant)
    FED_ACTION="${FED_POS[0]}"
    FED_POS=("${FED_POS[@]:1}")
    ;;
  "")
    echo "-- Needs a command: approve, create, grant or ungrant"
    fed_usage
    exit 1
    ;;
  *)
    echo "-- Unknown command '${FED_POS[0]}': expected approve, create, grant or ungrant"
    fed_usage
    exit 1
    ;;
esac

# The config of Afon, if there is one, for what is not given.  Not needed if all is given.
if [ -z "${AZURE_RESOURCE_GROUP:-}" ] || [ -z "${AZURE_APP_ID:-}" ] || [ -z "${CLUSTER_NAME:-}" ]; then
  AFON_HOME="${AFON_HOME:-$HOME/.afon}"
  if [ -f "$AFON_HOME/config/afon-azure.properties" ] && [ -f "$AFON_HOME/config/catalogs-azure.properties" ]; then
    source $AFON_ROOT/scripts/config/afon-source.sh azure
  fi
  if [ -f "$AFON_HOME/config/secret.properties" ] && [ -f "$AFON_HOME/config/secret-azure.properties" ]; then
    source $AFON_ROOT/scripts/config/secret-source.sh azure
  fi
fi
FED_RG="${FED_RG:-${AZURE_RESOURCE_GROUP:-}}"
FED_APP_ID="${FED_APP_ID:-${APP_ID:-${AZURE_APP_ID:-}}}"
if [ -z "$FED_APP_ID" ]; then
  echo "-- The app is not known: give --app-id <client id>, or set AZURE_APP_ID in the config"
  exit 1
fi

if [ "$FED_ACTION" = "grant" ] || [ "$FED_ACTION" = "ungrant" ]; then
  if [ -z "$FED_DEPLOYER" ]; then
    # the identity of the login session: a user, by its object ID, or a service principal, by its client ID
    FED_SESSION="$(az account show --query "[user.type, user.name]" -o tsv 2>/dev/null)" || FED_SESSION=""
    FED_SESSION_TYPE="$(echo "$FED_SESSION" | sed -n 1p)"
    FED_SESSION_NAME="$(echo "$FED_SESSION" | sed -n 2p)"
    case "$FED_SESSION_TYPE" in
      user)
        FED_DEPLOYER="$(az ad signed-in-user show --query id -o tsv 2>/dev/null)" || FED_DEPLOYER=""
        [ -n "$FED_DEPLOYER" ] || FED_DEPLOYER="$FED_SESSION_NAME"
        ;;
      servicePrincipal)
        FED_DEPLOYER="$FED_SESSION_NAME"
        ;;
    esac
    if [ -z "$FED_DEPLOYER" ] || [ "$FED_DEPLOYER" = "systemAssignedIdentity" ] || [ "$FED_DEPLOYER" = "userAssignedIdentity" ]; then
      echo "-- Could not find the deployer from the az login: give --deployer <user principal name, client id or object id>"
      exit 1
    fi
    FED_DEPLOYER_LABEL="$FED_SESSION_NAME"
    FED_IS_SESSION=true
    echo "- The deployer is the identity that is logged in to az: ${FED_SESSION_TYPE} ${FED_SESSION_NAME}"
  fi
  # the object ID of the deployer: of a user, by its user principal name or object ID, else of a service principal, by its client ID or object ID
  FED_GRANTEE_ID="$(az ad user show --id "$FED_DEPLOYER" --query id -o tsv 2>/dev/null)" || FED_GRANTEE_ID=""
  FED_GRANTEE_KIND="user"
  if [ -z "$FED_GRANTEE_ID" ]; then
    FED_GRANTEE_ID="$(az ad sp show --id "$FED_DEPLOYER" --query id -o tsv 2>/dev/null)" || FED_GRANTEE_ID=""
    FED_GRANTEE_KIND="service principal"
  fi
  if [ -z "$FED_GRANTEE_ID" ]; then
    echo "-- No user or service principal '$FED_DEPLOYER': give a user principal name, eg someone@example.com, or a client ID, or an object ID"
    exit 1
  fi
  if [ "$FED_ACTION" = "grant" ]; then FED_VERB="Granting the app registration role on the app '$FED_APP_ID' to"; else FED_VERB="Taking the app registration role on the app '$FED_APP_ID' from"; fi
  echo "- $FED_VERB the $FED_GRANTEE_KIND '${FED_DEPLOYER_LABEL:-$FED_DEPLOYER}' ($FED_GRANTEE_ID)"
  FED_HOLDERS="$(az ad app owner list --id "$FED_APP_ID" --query "[].id" -o tsv)" || {
    echo "-- Could not list who has the app registration role on the app: is the az login allowed to read it?"
    exit 1
  }
  if [ "$FED_ACTION" = "ungrant" ]; then
    if echo "$FED_HOLDERS" | grep -qx "$FED_GRANTEE_ID"; then
      if [ "$FED_FORCE" != "true" ]; then
        if [ "${FED_IS_SESSION:-false}" = "true" ]; then
          echo "-- Refusing: the deployer is the identity that is logged in, which would lose the rights to change the app and could not give them back."
          echo "-- Run it as another identity that has the role, giving --deployer, or add --force"
          exit 1
        fi
        if [ "$(echo "$FED_HOLDERS" | grep -c .)" -le 1 ]; then
          echo "-- Refusing: it is the last that has the app registration role, and then only a tenant admin could give it back.  Give it to another first, or add --force"
          exit 1
        fi
      fi
      az ad app owner remove --id "$FED_APP_ID" --owner-object-id "$FED_GRANTEE_ID" || exit 1
      echo "- Taken.  The deploy now fails fast when it needs to give the app the federated credential of a new cluster"
    else
      echo "- It does not have the role, skipping"
    fi
  elif echo "$FED_HOLDERS" | grep -qx "$FED_GRANTEE_ID"; then
    echo "- It has the role already, skipping"
  else
    az ad app owner add --id "$FED_APP_ID" --owner-object-id "$FED_GRANTEE_ID" || exit 1
    echo "- Granted.  The deploy can now give the app the federated credential of each new cluster"
  fi
  if [ "$FED_ACTION" = "grant" ] && [ "$FED_GRANTEE_KIND" = "service principal" ]; then
    echo "- A service principal also needs the Microsoft Graph application permission Application.ReadWrite.OwnedBy, with the consent of an admin"
  fi
else
case "$FED_ACTION" in
  approve)
    if [ -z "$FED_CLUSTER" ]; then
      if [ -z "$FED_RG" ]; then
        echo "-- The resource group is not known: give --resource-group <name>, or set AZURE_RESOURCE_GROUP in the config"
        exit 1
      fi
      source $AFON_ROOT/scripts/azure/polaris-cluster-source.sh
      azure_polaris_cluster "$FED_RG" "${FED_POS[0]:-}" || { echo "-- Or give --cluster <name>"; exit 1; }
      FED_CLUSTER="$STACK_CLUSTER_NAME"
    elif [ -z "$FED_RG" ]; then
      echo "-- The resource group is not known: give --resource-group <name>, or set AZURE_RESOURCE_GROUP in the config"
      exit 1
    fi
    FED_NAME="afon-$FED_CLUSTER"
    FED_SUBJECT="$FED_POLARIS_SUBJECT"
    FED_ISSUER="$(az aks show --resource-group "$FED_RG" --name "$FED_CLUSTER" --query oidcIssuerProfile.issuerUrl -o tsv 2>/dev/null)"
    if [ -z "$FED_ISSUER" ]; then
      echo "-- Could not get the OIDC issuer of the AKS cluster '$FED_CLUSTER' in $FED_RG: does it exist, and may the az login read it?"
      exit 1
    fi
    echo "- The cluster '$FED_CLUSTER' in $FED_RG reports this issuer, which would act as the app '$FED_APP_ID':"
    echo "-- name:      $FED_NAME"
    echo "-- issuer:    $FED_ISSUER"
    echo "-- subject:   $FED_SUBJECT   (the service account of Polaris, in the cluster)"
    echo "-- audiences: $FED_AUDIENCE"
    if [ "$FED_YES" != "true" ]; then
      if [ ! -t 0 ]; then
        echo "-- Review the above.  There is no terminal to ask in, as when run by afon-cli.sh: to approve, run the same command again with -y"
        exit 1
      fi
      read -r -p "Approve, so that any pod running as that service account of that cluster can act as the app? [y/N] " FED_REPLY
      case "$FED_REPLY" in
        y|Y|yes|YES) ;;
        *) echo "- Not approved"; exit 1 ;;
      esac
    fi
    ;;
  create)
    FED_NAME="${FED_POS[0]:-}"
    FED_ISSUER="${FED_POS[1]:-}"
    FED_SUBJECT="${FED_POS[2]:-}"
    if [ -z "$FED_NAME" ] || [ -z "$FED_ISSUER" ] || [ -z "$FED_SUBJECT" ]; then
      fed_usage
      exit 1
    fi
    ;;
esac

echo "- Giving the app '$FED_APP_ID' a federated credential '$FED_NAME'"
FED_PARAMETERS="{\"name\":\"$FED_NAME\",\"issuer\":\"$FED_ISSUER\",\"subject\":\"$FED_SUBJECT\",\"audiences\":[\"$FED_AUDIENCE\"]}"
FED_EXISTING="$(az ad app federated-credential list --id "$FED_APP_ID" \
  --query "[?issuer=='$FED_ISSUER' && subject=='$FED_SUBJECT'].name" -o tsv)" || {
  echo "-- Could not list the federated credentials of the app: is the az login allowed to read it?"
  exit 1
}
FED_SAME_NAME="$(az ad app federated-credential list --id "$FED_APP_ID" --query "[?name=='$FED_NAME'].issuer" -o tsv)"
if [ -n "$FED_EXISTING" ]; then
  echo "- The app already has one of this issuer and subject, skipping: $FED_EXISTING"
elif [ -n "$FED_SAME_NAME" ] && [ "$FED_ACTION" = "approve" ]; then
  # A cluster rebuilt has the same name and a new issuer, so the credential of its previous build has the name, and is replaced, as it can only
  # be of a cluster that is gone: a cluster is named by its stack, and two do not run with the same name.  One that is given is not replaced.
  echo "- The app has a credential '$FED_NAME' for another issuer, that of a previous build of the cluster: $FED_SAME_NAME"
  echo "- Replacing it with the issuer above"
  az ad app federated-credential update --id "$FED_APP_ID" --federated-credential-id "$FED_NAME" --parameters "$FED_PARAMETERS" || exit 1
  echo "- Replaced. It may take a few minutes to take effect"
elif [ -n "$FED_SAME_NAME" ]; then
  echo "-- The app has a credential '$FED_NAME' already, for the issuer $FED_SAME_NAME: give another name, or delete it with"
  echo "-- az ad app federated-credential delete --id $FED_APP_ID --federated-credential-id $FED_NAME"
  exit 1
else
  az ad app federated-credential create --id "$FED_APP_ID" --parameters "$FED_PARAMETERS" -o none || exit 1
  echo "- Created. It may take a few minutes to take effect"
fi
fi
