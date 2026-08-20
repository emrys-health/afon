source $AFON_ROOT/scripts/config/afon-source.sh aws

aws iam detach-role-policy --role-name S3ReadWriteRoleSA --policy-arn arn:aws:iam::418295715997:policy/AfonBucketWhiteList
aws iam detach-role-policy --role-name S3ReadWriteRole   --policy-arn arn:aws:iam::aws:policy/AfonBucketWhiteList
aws iam delete-role --role-name S3ReadWriteRole
aws s3 rm s3://$AWS_S3_BUCKET_NAME --recursive
aws s3api delete-bucket --bucket $AWS_S3_BUCKET_NAME
aws s3api delete-bucket --bucket $AWS_S3_EXTERNAL_BUCKET_NAME

aws iam delete-policy --policy-arn $(aws iam list-policies --scope Local --query "Policies[?PolicyName=='AWSLoadBalancerControllerIAMPolicy'].Arn" --output text)

aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn  arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList
aws iam delete-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList
