spark-connect-gcs-read-smoke

The GCS counterpart of spark-connect-s3-read-smoke: reads a text file from a public (anonymously readable)
Google Cloud Storage bucket over Spark Connect.  Requires the Spark Connect driver port to be forwarded:
  kubectl port-forward pod/spark-connector-0-driver 15002:15002

Usage:
  afon-cli.sh spark-connect-gcs-read-smoke
