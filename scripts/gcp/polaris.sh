NETWORKS=$(gcloud compute networks list --filter="name=gke-private-vpc-polaris" --format="value(name)")
if [ -n "$NETWORKS" ]; then
  echo "GCP Polaris running: $NETWORKS"
else
  echo "No GCP Polaris running"
fi
