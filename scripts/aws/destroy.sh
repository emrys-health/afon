aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
aws iam delete-role --role-name S3ReadWriteRole
aws iam detach-group-policy --group-name AfonGroup --policy-arn arn:aws:iam::418295715997:policy/AssumeS3ReadRolePolicy
aws iam delete-group --group-name AfonGroup
aws s3 rm s3://emrys-afon-bucket --recursive
ws s3api delete-bucket --bucket  emrys-afon-bucket
