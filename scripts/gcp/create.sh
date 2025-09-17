gcloud storage buckets create "gs://emrys_afon_core" --location=europe-west2
gcloud iam service-accounts create my-vendable-sa --display-name "Vendable Service Account"
gcloud iam service-accounts add-iam-policy-binding my-vendable-sa@fjords-450014.iam.gserviceaccount.com   --member="user:${GCP_USER_ACCOUNT}"   --role="roles/iam.serviceAccountTokenCreator"
# This required me to use my browser to authenticate so I am not sure how best to automate it:
gcloud auth application-default login --impersonate-service-account=my-vendable-sa@fjords-450014.iam.gserviceaccount.com
gcloud storage buckets add-iam-policy-binding gs://emrys_afon_core  --member="serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"
