echo "gcloud login"
# --update-adc also writes the login to the Application Default Credentials, which the stacks authenticate with,
# so that the gcloud commands of the scripts and the stacks run as the same user
gcloud auth login --update-adc
