# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && source afon-gcp.properties && set +a

# The call to "gcloud auth application-default login ..." below required me to use my browser to authenticate so I am not sure how best to automate it:
gcloud storage buckets create "gs://$GCP_BUCKET_NAME" --location=europe-west2 && \
gcloud iam service-accounts create my-vendable-sa --display-name "Vendable Service Account" && \
gcloud iam service-accounts add-iam-policy-binding $GCP_SERVICE_ACCOUNT   --member="user:${GCP_USER_ACCOUNT}"   --role="roles/iam.serviceAccountTokenCreator" && \
gcloud auth application-default login --impersonate-service-account=$GCP_SERVICE_ACCOUNT && \
gcloud storage buckets add-iam-policy-binding gs://$GCP_BUCKET_NAME  --member="serviceAccount:$GCP_SERVICE_ACCOUNT"  --role="roles/storage.objectAdmin"

gcloud iam service-accounts create external-sa --display-name "Mock External Service Account" && \
gcloud iam service-accounts add-iam-policy-binding external-sa@fjords-450014.iam.gserviceaccount.com --member="serviceAccount:$GCP_SERVICE_ACCOUNT"  --role="roles/iam.serviceAccountTokenCreator" && \
gcloud auth application-default login --impersonate-service-account=external-sa@fjords-450014.iam.gserviceaccount.com && \
gcloud storage buckets add-iam-policy-binding gs://$GCP_EXTERNAL_BUCKET_NAME  --member="serviceAccount:external-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"

gcloud storage buckets create "gs://$GCP_BUCKET_NAME_FORBIDDEN" --location=europe-west2

gcloud iam service-accounts add-iam-policy-binding \
    $GCP_SERVICE_ACCOUNT \
    --member="serviceAccount:blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com" \
    --role="roles/iam.serviceAccountTokenCreator" \
    --project fjords-450014
gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT

# Needed when we move to Polaris 1.4
gcloud services enable iam.googleapis.com --project=fjords-450014
gcloud services enable iamcredentials.googleapis.com --project=fjords-450014
gcloud services enable serviceusage.googleapis.com --project=fjords-450014
gcloud services enable storage.googleapis.com --project=fjords-450014
gcloud projects add-iam-policy-binding fjords-450014 --member='serviceAccount:$GCP_SERVICE_ACCOUNT' --role='roles/storage.objectViewer'
gcloud projects add-iam-policy-binding fjords-450014 \
    --member="serviceAccount:$GCP_SERVICE_ACCOUNT" \
    --role="roles/serviceusage.serviceUsageConsumer"

