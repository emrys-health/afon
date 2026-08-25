source $AFON_ROOT/scripts/config/afon-source.sh gcp

PROJECT_ID="fjords-450014"
EXTERNAL_SA="external-sa@$PROJECT_ID.iam.gserviceaccount.com"

gcloud auth application-default login --impersonate-service-account=$EXTERNAL_SA