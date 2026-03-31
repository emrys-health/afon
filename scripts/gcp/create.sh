# The call to "gcloud auth application-default login ..." below required me to use my browser to authenticate so I am not sure how best to automate it:
gcloud storage buckets create "gs://emrys_afon_core" --location=europe-west2 && \
gcloud iam service-accounts create my-vendable-sa --display-name "Vendable Service Account" && \
gcloud iam service-accounts add-iam-policy-binding my-vendable-sa@fjords-450014.iam.gserviceaccount.com   --member="user:phil.henry@emrys.health"   --role="roles/iam.serviceAccountTokenCreator" && \
gcloud auth application-default login --impersonate-service-account=my-vendable-sa@fjords-450014.iam.gserviceaccount.com && \
gcloud storage buckets add-iam-policy-binding gs://emrys_afon_core  --member="serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"

gcloud iam service-accounts create external-sa --display-name "Mock External Service Account" && \
gcloud iam service-accounts add-iam-policy-binding external-sa@fjords-450014.iam.gserviceaccount.com --member="serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/iam.serviceAccountTokenCreator" && \
gcloud auth application-default login --impersonate-service-account=external-sa@fjords-450014.iam.gserviceaccount.com && \
gcloud storage buckets add-iam-policy-binding gs://emrys_afon_core_2  --member="serviceAccount:external-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"

gcloud storage buckets create "gs://emrys_afon_core_forbidden" --location=europe-west2

gcloud iam service-accounts add-iam-policy-binding \
    my-vendable-sa@fjords-450014.iam.gserviceaccount.com \
    --member="serviceAccount:blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com" \
    --role="roles/iam.serviceAccountTokenCreator" \
    --project fjords-450014
gcloud iam service-accounts get-iam-policy my-vendable-sa@fjords-450014.iam.gserviceaccount.com

# Needed when we move to Polaris 1.4
gcloud services enable iam.googleapis.com --project=fjords-450014
gcloud services enable iamcredentials.googleapis.com --project=fjords-450014
gcloud services enable serviceusage.googleapis.com --project=fjords-450014
gcloud services enable storage.googleapis.com --project=fjords-450014
gcloud projects add-iam-policy-binding fjords-450014 --member='serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com' --role='roles/storage.objectViewer'
gcloud projects add-iam-policy-binding fjords-450014 \
    --member="serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com" \
    --role="roles/serviceusage.serviceUsageConsumer"

