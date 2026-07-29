# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && source afon-gcp.properties && set +a

gcloud storage buckets remove-iam-policy-binding  gs://$GCP_BUCKET_NAME  --member="serviceAccount:$GCP_SERVICE_ACCOUNT"  --role="roles/storage.objectAdmin"
gcloud iam service-accounts delete  $GCP_SERVICE_ACCOUNT --quiet
gcloud storage rm -r gs://$GCP_BUCKET_NAME

gcloud storage buckets remove-iam-policy-binding  gs://$GCP_EXTERNAL_BUCKET_NAME  --member="serviceAccount:external-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"
gcloud iam service-accounts delete external-sa@fjords-450014.iam.gserviceaccount.com --quiet
gcloud storage rm -r gs://$GCP_EXTERNAL_BUCKET_NAME
