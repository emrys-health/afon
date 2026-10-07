source $AFON_ROOT/scripts/config/afon-source.sh gcp
source $AFON_ROOT/scripts/kube/polaris-source.sh

# Reports whether Polaris is up and serving on this env's GCP stack: login, GKE network, GKE cluster,
# Polaris pod, ingress external IP, TLS secret, then real HTTPS requests, anonymous and as the Polaris admin.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

POLARIS_PROJECT="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"

status_require_domain GCP_POLARIS_DOMAIN_NAME "$GCP_POLARIS_DOMAIN_NAME"
status_require gcloud kubectl curl
if ! command -v gke-gcloud-auth-plugin >/dev/null 2>&1; then
  status_cannot_verify "gke-gcloud-auth-plugin not found, cannot verify Polaris (install it with: afon-cli.sh local-setup -c gcp)"
fi

echo "- Checking Polaris for cluster '$CLUSTER_NAME' at $STATUS_DOMAIN"

status_require_login GCP gcp-login gcloud auth print-access-token

if NETWORKS=$(gcloud compute networks list --project "$POLARIS_PROJECT" --filter="name=gke-private-vpc-$CLUSTER_NAME" --format="value(name)" 2>"$STATUS_ERR"); then
  status_check_network "GKE network" "$NETWORKS"
else
  status_cannot_verify "- Could not list the GCP networks in project $POLARIS_PROJECT:"
fi

status_get_credentials GKE "404|not found" \
  env KUBECONFIG="$STATUS_KUBECONFIG" gcloud container clusters get-credentials "$CLUSTER_NAME" --region "$GCP_REGION" --project "$POLARIS_PROJECT"

status_check_nodes
status_check_pod app=polaris
status_check_ingress polaris-ingress ip "external IP"
status_check_tls polaris-tls
polaris_check_https
polaris_check_authenticated gcp
status_report
