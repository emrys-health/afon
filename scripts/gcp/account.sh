source $AFON_ROOT/scripts/config/afon-source.sh gcp

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"

echo "- Account project: $PROJECT_ID"
# The Application Default Credentials, which the stacks authenticate with, bill their API usage to a quota project:
# keep it the same as the project, else gcloud warns of unexpected quota issues.  Set first, so that selecting
# the project below finds them already matching.
gcloud auth application-default set-quota-project $PROJECT_ID
gcloud config set project $PROJECT_ID
USER_NAME=$(gcloud config get-value account 2>/dev/null)
echo "- Account user: $USER_NAME"
gcloud auth list
