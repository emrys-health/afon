gcloud storage buckets remove-iam-policy-binding  gs://emrys_afon_core  --member="serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"
gcloud iam service-accounts delete  my-vendable-sa@fjords-450014.iam.gserviceaccount.com --quiet
gcloud storage rm -r gs://emrys_afon_core
