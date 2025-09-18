aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn $AWS_S3_ROLE_ARN
aws iam delete-role --role-name S3ReadWriteRole
aws s3 rm s3://$AWS_S3_BUCKET_NAME --recursive
aws s3api delete-bucket --bucket  $AWS_S3_BUCKET_NAME
