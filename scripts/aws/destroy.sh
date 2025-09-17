aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
aws iam delete-role --role-name S3ReadWriteRole
aws s3 rm s3://emrys-afon-bucket --recursive
aws s3api delete-bucket --bucket  emrys-afon-bucket
