# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && set +a

aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
aws iam delete-role --role-name S3ReadWriteRole
aws s3 rm s3://$AWS_S3_BUCKET_NAME --recursive
aws s3api delete-bucket --bucket $AWS_S3_BUCKET_NAME
aws s3api delete-bucket --bucket emrys-afon-bucket-2

aws iam delete-policy --policy-arn $(aws iam list-policies --scope Local --query "Policies[?PolicyName=='AWSLoadBalancerControllerIAMPolicy'].Arn" --output text)

aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn  arn:aws:iam::123456789012:policy/AfonBucketWhiteList
aws iam delete-policy --policy-arn arn:aws:iam::123456789012:policy/AfonBucketWhiteList
