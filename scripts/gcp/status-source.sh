# Shared by the GCP status scripts, eg gcp/polaris.sh and gcp/keycloak.sh, which source it after the source of
# what they check, eg kube/polaris-source.sh.  Not run on its own.

# gcp_status_find_stack <stack id given, or empty> <stack ids>...: sets STACK_TYPE, and so STATUS_CLUSTER_NAME, to the
# type and the cluster <stack>-<CLUSTER_NAME> of the stack given, else of the one running among the stacks, found by
# its GKE cluster: as they hold a common exclusive resource, eg a domain name, only one of them runs at a time.  Reports
# FAILED if none runs, leaving STACK_TYPE and STATUS_CLUSTER_NAME empty, and could not verify if more than one does.
# The launcher gives the stack, or else the stacks that can serve what the script checks, see ScriptMain.
gcp_status_find_stack() {
  local given="$1" ids types="" clusters found="" type cluster id
  shift
  ids="$*"
  for id in $ids; do
    types="$types ${id#gcp-}"
  done
  types="${types# }"
  STACK_TYPE=
  if [ -n "$given" ]; then
    STACK_TYPE="${given#gcp-}"
  else
    if ! clusters=$(gcloud container clusters list --project "$GCP_PROJECT_ID" --format="value(name)" 2>"$STATUS_ERR"); then
      status_cannot_verify "- Could not list the GKE clusters in project $GCP_PROJECT_ID:"
    fi
    for type in $types; do
      for cluster in $clusters; do
        if [ "$cluster" == "$type-$CLUSTER_NAME" ]; then
          found="$found $type"
        fi
      done
    done
    case $(echo $found | wc -w | tr -d ' ') in
      0) status_failed "No stack serving $STATUS_SUBJECT found, ie no GKE cluster <stack>-$CLUSTER_NAME, for stacks: ${ids:-none}" ;;
      1) STACK_TYPE=$(echo $found) ;;
      *) status_cannot_verify "- More than one stack serving $STATUS_SUBJECT found:$found.  Give the stack as argument, by its id or its role, eg ${ids%% *}" ;;
    esac
  fi
  if [ -n "$STACK_TYPE" ]; then
    STATUS_CLUSTER_NAME="$STACK_TYPE-$CLUSTER_NAME"
  else
    STATUS_CLUSTER_NAME=
  fi
}

# gcp_status_check_cluster: the GKE network and the credentials of the cluster found, if any
gcp_status_check_cluster() {
  if [ -z "$STATUS_CLUSTER_NAME" ]; then
    return 0
  fi
  local networks
  if networks=$(gcloud compute networks list --project "$GCP_PROJECT_ID" --filter="name=gke-private-vpc-$STATUS_CLUSTER_NAME" --format="value(name)" 2>"$STATUS_ERR"); then
    status_check_network "GKE network" "$networks"
  else
    status_cannot_verify "- Could not list the GCP networks in project $GCP_PROJECT_ID:"
  fi

  status_get_credentials GKE "404|not found" \
    env KUBECONFIG="$STATUS_KUBECONFIG" gcloud container clusters get-credentials "$STATUS_CLUSTER_NAME" --region "$GCP_REGION" --project "$GCP_PROJECT_ID"
}
