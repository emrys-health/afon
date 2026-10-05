source $AFON_ROOT/scripts/config/afon-source.sh gcp

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"
EXTERNAL_SA="external-sa@$PROJECT_ID.iam.gserviceaccount.com"

gcloud auth application-default login --impersonate-service-account=$EXTERNAL_SA