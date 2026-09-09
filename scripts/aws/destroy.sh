source $AFON_ROOT/scripts/config/afon-source.sh aws

if aws iam get-role --role-name S3ReadWriteRoleSA >/dev/null 2>&1 && aws iam list-attached-role-policies --role-name S3ReadWriteRoleSA --query "AttachedPolicies[?PolicyArn=='arn:aws:iam::418295715997:policy/AfonBucketWhiteList'].PolicyArn" --output text | grep -q AfonBucketWhiteList; then
  echo "- Detaching policy arn:aws:iam::418295715997:policy/AfonBucketWhiteList from S3ReadWriteRoleSA"
  aws iam detach-role-policy --role-name S3ReadWriteRoleSA --policy-arn arn:aws:iam::418295715997:policy/AfonBucketWhiteList
else
  echo "- Policy arn:aws:iam::418295715997:policy/AfonBucketWhiteList not attached to S3ReadWriteRoleSA, skipping"
fi

if aws iam get-role --role-name S3ReadWriteRole >/dev/null 2>&1 && aws iam list-attached-role-policies --role-name S3ReadWriteRole --query "AttachedPolicies[?PolicyArn=='arn:aws:iam::aws:policy/AfonBucketWhiteList'].PolicyArn" --output text | grep -q AfonBucketWhiteList; then
  echo "- Detaching policy arn:aws:iam::aws:policy/AfonBucketWhiteList from S3ReadWriteRole"
  aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn arn:aws:iam::aws:policy/AfonBucketWhiteList
else
  echo "- Policy arn:aws:iam::aws:policy/AfonBucketWhiteList not attached to S3ReadWriteRole, skipping"
fi

if aws iam get-role --role-name S3ReadWriteRole >/dev/null 2>&1; then
  if aws iam list-attached-role-policies --role-name S3ReadWriteRole --query "AttachedPolicies[?PolicyArn=='arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList'].PolicyArn" --output text | grep -q AfonBucketWhiteList; then
    echo "- Detaching policy AfonBucketWhiteList from S3ReadWriteRole"
    aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList
  else
    echo "- Policy AfonBucketWhiteList not attached to S3ReadWriteRole, skipping"
  fi

  echo "- Deleting role S3ReadWriteRole"
  aws iam delete-role --role-name S3ReadWriteRole
else
  echo "- Role S3ReadWriteRole does not exist, skipping"
fi

for BUCKET in $AWS_S3_BUCKET_NAME_EXTERNAL $AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS $AWS_S3_BUCKET_NAME_TRINO $AWS_S3_BUCKET_NAME $AWS_S3_BUCKET_NAME_FORBIDDEN; do
  if aws s3api head-bucket --bucket $BUCKET 2>/dev/null; then
    echo "- Emptying and deleting bucket $BUCKET"
    aws s3 rm s3://$BUCKET --recursive
    aws s3api delete-bucket --bucket $BUCKET
  else
    echo "- Bucket $BUCKET does not exist, skipping"
  fi
done

if aws iam get-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList >/dev/null 2>&1; then
  echo "- Deleting policy AfonBucketWhiteList"
  aws iam delete-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList
else
  echo "- Policy AfonBucketWhiteList does not exist, skipping"
fi

if aws iam get-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy >/dev/null 2>&1; then
  echo "- Deleting policy AWSLoadBalancerControllerIAMPolicy"
  aws iam delete-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy
else
  echo "- Policy AWSLoadBalancerControllerIAMPolicy does not exist, skipping"
fi
