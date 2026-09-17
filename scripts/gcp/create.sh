source $AFON_ROOT/scripts/config/afon-source.sh gcp

PROJECT_ID="fjords-450014"
EXTERNAL_SA="external-sa@$PROJECT_ID.iam.gserviceaccount.com"
BLIRC_SA="blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com"

if gcloud storage buckets describe "gs://$GCP_BUCKET_NAME" >/dev/null 2>&1; then
  echo "- Bucket $GCP_BUCKET_NAME already exists, skipping"
else
  echo "- Creating bucket $GCP_BUCKET_NAME"
  gcloud storage buckets create "gs://$GCP_BUCKET_NAME" --location=europe-west2 || exit 1
fi

if gcloud iam service-accounts describe $GCP_SERVICE_ACCOUNT >/dev/null 2>&1; then
  echo "- Service account $GCP_SERVICE_ACCOUNT already exists, skipping"
else
  echo "- Creating service account my-vendable-sa"
  gcloud iam service-accounts create my-vendable-sa --display-name "Vendable Service Account" || exit 1
fi

if [ -n "$GCP_USER_ACCOUNT" ]; then
  if gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=user:$GCP_USER_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Role iam.serviceAccountTokenCreator already granted to $GCP_USER_ACCOUNT on $GCP_SERVICE_ACCOUNT, skipping"
  else
    echo "- Granting iam.serviceAccountTokenCreator on $GCP_SERVICE_ACCOUNT to $GCP_USER_ACCOUNT"
    gcloud iam service-accounts add-iam-policy-binding $GCP_SERVICE_ACCOUNT --member="user:$GCP_USER_ACCOUNT" --role="roles/iam.serviceAccountTokenCreator" || exit 1
  fi
fi

if gcloud storage buckets get-iam-policy "gs://$GCP_BUCKET_NAME" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role storage.objectAdmin already granted to $GCP_SERVICE_ACCOUNT on $GCP_BUCKET_NAME, skipping"
else
  echo "- Granting storage.objectAdmin on $GCP_BUCKET_NAME to $GCP_SERVICE_ACCOUNT"
  gcloud storage buckets add-iam-policy-binding "gs://$GCP_BUCKET_NAME" --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/storage.objectAdmin" || exit 1
fi

if gcloud iam service-accounts describe $EXTERNAL_SA >/dev/null 2>&1; then
  echo "- Service account $EXTERNAL_SA already exists, skipping"
else
  echo "- Creating service account external-sa"
  gcloud iam service-accounts create external-sa --display-name "Mock External Service Account" || exit 1
fi

if gcloud iam service-accounts get-iam-policy $EXTERNAL_SA --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role iam.serviceAccountTokenCreator already granted to $GCP_SERVICE_ACCOUNT on $EXTERNAL_SA, skipping"
else
  echo "- Granting iam.serviceAccountTokenCreator on $EXTERNAL_SA to $GCP_SERVICE_ACCOUNT"
  gcloud iam service-accounts add-iam-policy-binding $EXTERNAL_SA --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/iam.serviceAccountTokenCreator" || exit 1
fi

if gcloud storage buckets get-iam-policy "gs://$GCP_BUCKET_NAME_EXTERNAL" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$EXTERNAL_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role storage.objectAdmin already granted to $EXTERNAL_SA on $GCP_BUCKET_NAME_EXTERNAL, skipping"
else
  echo "- Granting storage.objectAdmin on $GCP_BUCKET_NAME_EXTERNAL to $EXTERNAL_SA"
  gcloud storage buckets add-iam-policy-binding "gs://$GCP_BUCKET_NAME_EXTERNAL" --member="serviceAccount:$EXTERNAL_SA" --role="roles/storage.objectAdmin" || exit 1
fi

if gcloud storage buckets describe "gs://$GCP_BUCKET_NAME_FORBIDDEN" >/dev/null 2>&1; then
  echo "- Bucket $GCP_BUCKET_NAME_FORBIDDEN already exists, skipping"
else
  echo "- Creating bucket $GCP_BUCKET_NAME_FORBIDDEN"
  gcloud storage buckets create "gs://$GCP_BUCKET_NAME_FORBIDDEN" --location=europe-west2 || exit 1
fi

if gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=serviceAccount:$BLIRC_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role iam.serviceAccountTokenCreator already granted to $BLIRC_SA on $GCP_SERVICE_ACCOUNT, skipping"
else
  echo "- Granting iam.serviceAccountTokenCreator on $GCP_SERVICE_ACCOUNT to $BLIRC_SA"
  gcloud iam service-accounts add-iam-policy-binding \
      $GCP_SERVICE_ACCOUNT \
      --member="serviceAccount:$BLIRC_SA" \
      --role="roles/iam.serviceAccountTokenCreator" \
      --project $PROJECT_ID || exit 1
fi
gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT

# Needed when we move to Polaris 1.4
for API in iam.googleapis.com iamcredentials.googleapis.com serviceusage.googleapis.com storage.googleapis.com; do
  if gcloud services list --enabled --project=$PROJECT_ID --filter="config.name=$API" --format="value(config.name)" 2>/dev/null | grep -q "^$API$"; then
    echo "- API $API already enabled, skipping"
  else
    echo "- Enabling API $API"
    gcloud services enable $API --project=$PROJECT_ID || exit 1
  fi
done

if gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectViewer AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role storage.objectViewer already granted to $GCP_SERVICE_ACCOUNT on project $PROJECT_ID, skipping"
else
  echo "- Granting storage.objectViewer on project $PROJECT_ID to $GCP_SERVICE_ACCOUNT"
  gcloud projects add-iam-policy-binding $PROJECT_ID --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/storage.objectViewer" || exit 1
fi

if gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.role=roles/serviceusage.serviceUsageConsumer AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role serviceusage.serviceUsageConsumer already granted to $GCP_SERVICE_ACCOUNT on project $PROJECT_ID, skipping"
else
  echo "- Granting serviceusage.serviceUsageConsumer on project $PROJECT_ID to $GCP_SERVICE_ACCOUNT"
  gcloud projects add-iam-policy-binding $PROJECT_ID \
      --member="serviceAccount:$GCP_SERVICE_ACCOUNT" \
      --role="roles/serviceusage.serviceUsageConsumer" || exit 1
fi

echo "GCP env created."
