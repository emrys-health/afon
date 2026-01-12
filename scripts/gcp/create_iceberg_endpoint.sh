gcloud services enable \
    bigquery.googleapis.com \
    bigqueryconnection.googleapis.com \
    biglake.googleapis.com

#PROJECT_ID="afon-core"
#LOCATION="europe-west2"
#CATALOG_ID="afon_iceberg_catalog"
#
#gcloud alpha biglake iceberg catalogs create $CATALOG_ID --project=$PROJECT_ID --catalog-type=gcs-bucket

gcloud storage buckets create "gs://emrys_afon_core_2" --location=europe-west2
#gcloud storage buckets add-iam-policy-binding gs://emrys_afon_core_2  --member="serviceAccount:my-vendable-sa@fjords-450014.iam.gserviceaccount.com"  --role="roles/storage.objectAdmin"
gcloud storage buckets add-iam-policy-binding gs://emrys_afon_core_2 --member="serviceAccount:blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com" --role="roles/storage.objectUser"