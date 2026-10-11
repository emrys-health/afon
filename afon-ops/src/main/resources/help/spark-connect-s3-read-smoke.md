spark-connect-s3-read-smoke

Smoke test reading a text file from a public (anonymously readable) S3 bucket over Spark Connect, which must
fail where reads of it are meant to be blocked.  The S3A settings are passed as read options, not as
spark.hadoop.* builder configs, as these never reach the Spark Connect server's Hadoop configuration.

Usage:
  afon-cli.sh spark-connect-s3-read-smoke
