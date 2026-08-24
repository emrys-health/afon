source $AFON_ROOT/scripts/config/afon-source.sh gcp

for API in bigquery.googleapis.com bigqueryconnection.googleapis.com biglake.googleapis.com iamcredentials.googleapis.com; do
  if gcloud services list --enabled --filter="config.name=$API" --format="value(config.name)" 2>/dev/null | grep -q "^$API$"; then
    echo "- API $API already enabled, skipping"
  else
    echo "- Enabling API $API"
    gcloud services enable $API || exit 1
  fi
done

#PROJECT_ID="afon-core"
#LOCATION="europe-west2"
#CATALOG_ID="afon_iceberg_catalog"
#
#gcloud alpha biglake iceberg catalogs create $CATALOG_ID --project=$PROJECT_ID --catalog-type=gcs-bucket

ICEBERG_BUCKET="emrys_afon_core_2"
EXTERNAL_SA="external-sa@fjords-450014.iam.gserviceaccount.com"
BLIRC_SA="blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com"

if gcloud storage buckets describe "gs://$ICEBERG_BUCKET" >/dev/null 2>&1; then
  echo "- Bucket $ICEBERG_BUCKET already exists, skipping"
else
  echo "- Creating bucket $ICEBERG_BUCKET"
  gcloud storage buckets create "gs://$ICEBERG_BUCKET" --location=europe-west2 || exit 1
fi

if gcloud storage buckets get-iam-policy "gs://$ICEBERG_BUCKET" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$EXTERNAL_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role storage.objectAdmin already granted to $EXTERNAL_SA on $ICEBERG_BUCKET, skipping"
else
  echo "- Granting storage.objectAdmin on $ICEBERG_BUCKET to $EXTERNAL_SA"
  gcloud storage buckets add-iam-policy-binding "gs://$ICEBERG_BUCKET" --member="serviceAccount:$EXTERNAL_SA" --role="roles/storage.objectAdmin" || exit 1
fi

if gcloud storage buckets get-iam-policy "gs://$ICEBERG_BUCKET" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectUser AND bindings.members=serviceAccount:$BLIRC_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role storage.objectUser already granted to $BLIRC_SA on $ICEBERG_BUCKET, skipping"
else
  echo "- Granting storage.objectUser on $ICEBERG_BUCKET to $BLIRC_SA"
  gcloud storage buckets add-iam-policy-binding "gs://$ICEBERG_BUCKET" --member="serviceAccount:$BLIRC_SA" --role="roles/storage.objectUser" || exit 1
fi

echo "GCP iceberg endpoint created."
