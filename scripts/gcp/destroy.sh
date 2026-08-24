source $AFON_ROOT/scripts/config/afon-source.sh gcp

PROJECT_ID="fjords-450014"
EXTERNAL_SA="external-sa@$PROJECT_ID.iam.gserviceaccount.com"
BLIRC_SA="blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com"
ICEBERG_BUCKET="emrys_afon_core_2"

if gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.role=roles/serviceusage.serviceUsageConsumer AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Revoking serviceusage.serviceUsageConsumer on project $PROJECT_ID from $GCP_SERVICE_ACCOUNT"
  gcloud projects remove-iam-policy-binding $PROJECT_ID --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/serviceusage.serviceUsageConsumer"
else
  echo "- Role serviceusage.serviceUsageConsumer not granted to $GCP_SERVICE_ACCOUNT on project $PROJECT_ID, skipping"
fi

if gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectViewer AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Revoking storage.objectViewer on project $PROJECT_ID from $GCP_SERVICE_ACCOUNT"
  gcloud projects remove-iam-policy-binding $PROJECT_ID --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/storage.objectViewer"
else
  echo "- Role storage.objectViewer not granted to $GCP_SERVICE_ACCOUNT on project $PROJECT_ID, skipping"
fi

if gcloud iam service-accounts describe $GCP_SERVICE_ACCOUNT >/dev/null 2>&1; then
  if gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=serviceAccount:$BLIRC_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Revoking iam.serviceAccountTokenCreator on $GCP_SERVICE_ACCOUNT from $BLIRC_SA"
    gcloud iam service-accounts remove-iam-policy-binding $GCP_SERVICE_ACCOUNT --member="serviceAccount:$BLIRC_SA" --role="roles/iam.serviceAccountTokenCreator" --project $PROJECT_ID
  else
    echo "- Role iam.serviceAccountTokenCreator not granted to $BLIRC_SA on $GCP_SERVICE_ACCOUNT, skipping"
  fi

  if gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=user:phil.henry@emrys.health" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Revoking iam.serviceAccountTokenCreator on $GCP_SERVICE_ACCOUNT from phil.henry@emrys.health"
    gcloud iam service-accounts remove-iam-policy-binding $GCP_SERVICE_ACCOUNT --member="user:phil.henry@emrys.health" --role="roles/iam.serviceAccountTokenCreator"
  else
    echo "- Role iam.serviceAccountTokenCreator not granted to phil.henry@emrys.health on $GCP_SERVICE_ACCOUNT, skipping"
  fi
else
  echo "- Service account $GCP_SERVICE_ACCOUNT does not exist, skipping its role bindings"
fi

if gcloud storage buckets describe "gs://$GCP_BUCKET_NAME" >/dev/null 2>&1; then
  if gcloud storage buckets get-iam-policy "gs://$GCP_BUCKET_NAME" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Revoking storage.objectAdmin on $GCP_BUCKET_NAME from $GCP_SERVICE_ACCOUNT"
    gcloud storage buckets remove-iam-policy-binding "gs://$GCP_BUCKET_NAME" --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/storage.objectAdmin"
  else
    echo "- Role storage.objectAdmin not granted to $GCP_SERVICE_ACCOUNT on $GCP_BUCKET_NAME, skipping"
  fi
  echo "- Deleting bucket $GCP_BUCKET_NAME"
  gcloud storage rm -r "gs://$GCP_BUCKET_NAME"
else
  echo "- Bucket $GCP_BUCKET_NAME does not exist, skipping"
fi

if gcloud iam service-accounts describe $GCP_SERVICE_ACCOUNT >/dev/null 2>&1; then
  echo "- Deleting service account $GCP_SERVICE_ACCOUNT"
  gcloud iam service-accounts delete $GCP_SERVICE_ACCOUNT --quiet
else
  echo "- Service account $GCP_SERVICE_ACCOUNT does not exist, skipping"
fi

if gcloud storage buckets describe "gs://$GCP_EXTERNAL_BUCKET_NAME" >/dev/null 2>&1; then
  if gcloud storage buckets get-iam-policy "gs://$GCP_EXTERNAL_BUCKET_NAME" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$EXTERNAL_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Revoking storage.objectAdmin on $GCP_EXTERNAL_BUCKET_NAME from $EXTERNAL_SA"
    gcloud storage buckets remove-iam-policy-binding "gs://$GCP_EXTERNAL_BUCKET_NAME" --member="serviceAccount:$EXTERNAL_SA" --role="roles/storage.objectAdmin"
  else
    echo "- Role storage.objectAdmin not granted to $EXTERNAL_SA on $GCP_EXTERNAL_BUCKET_NAME, skipping"
  fi
  echo "- Deleting bucket $GCP_EXTERNAL_BUCKET_NAME"
  gcloud storage rm -r "gs://$GCP_EXTERNAL_BUCKET_NAME"
else
  echo "- Bucket $GCP_EXTERNAL_BUCKET_NAME does not exist, skipping"
fi

if gcloud iam service-accounts describe $EXTERNAL_SA >/dev/null 2>&1; then
  echo "- Deleting service account $EXTERNAL_SA"
  gcloud iam service-accounts delete $EXTERNAL_SA --quiet
else
  echo "- Service account $EXTERNAL_SA does not exist, skipping"
fi

if gcloud storage buckets describe "gs://$GCP_BUCKET_NAME_FORBIDDEN" >/dev/null 2>&1; then
  echo "- Deleting bucket $GCP_BUCKET_NAME_FORBIDDEN"
  gcloud storage rm -r "gs://$GCP_BUCKET_NAME_FORBIDDEN"
else
  echo "- Bucket $GCP_BUCKET_NAME_FORBIDDEN does not exist, skipping"
fi

if gcloud storage buckets describe "gs://$ICEBERG_BUCKET" >/dev/null 2>&1; then
  if gcloud storage buckets get-iam-policy "gs://$ICEBERG_BUCKET" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$EXTERNAL_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Revoking storage.objectAdmin on $ICEBERG_BUCKET from $EXTERNAL_SA"
    gcloud storage buckets remove-iam-policy-binding "gs://$ICEBERG_BUCKET" --member="serviceAccount:$EXTERNAL_SA" --role="roles/storage.objectAdmin"
  else
    echo "- Role storage.objectAdmin not granted to $EXTERNAL_SA on $ICEBERG_BUCKET, skipping"
  fi
  if gcloud storage buckets get-iam-policy "gs://$ICEBERG_BUCKET" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectUser AND bindings.members=serviceAccount:$BLIRC_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Revoking storage.objectUser on $ICEBERG_BUCKET from $BLIRC_SA"
    gcloud storage buckets remove-iam-policy-binding "gs://$ICEBERG_BUCKET" --member="serviceAccount:$BLIRC_SA" --role="roles/storage.objectUser"
  else
    echo "- Role storage.objectUser not granted to $BLIRC_SA on $ICEBERG_BUCKET, skipping"
  fi
  echo "- Deleting bucket $ICEBERG_BUCKET"
  gcloud storage rm -r "gs://$ICEBERG_BUCKET"
else
  echo "- Bucket $ICEBERG_BUCKET does not exist, skipping"
fi
