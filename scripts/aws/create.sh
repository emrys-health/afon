source $AFON_ROOT/scripts/config/afon-source.sh aws

for BUCKET in $AWS_S3_BUCKET_NAME_EXTERNAL $AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS $AWS_S3_BUCKET_NAME_TRINO $AWS_S3_BUCKET_NAME $AWS_S3_BUCKET_NAME_FORBIDDEN; do
  if aws s3api head-bucket --bucket $BUCKET 2>/dev/null; then
    echo "- Bucket $BUCKET already exists, skipping"
  else
    echo "- Creating bucket $BUCKET"
    aws s3api create-bucket --bucket $BUCKET --region $AWS_REGION --create-bucket-configuration LocationConstraint=$AWS_REGION || exit 1
  fi
done

if aws iam get-role --role-name S3ReadWriteRole >/dev/null 2>&1; then
  echo "- Role S3ReadWriteRole already exists, skipping"
else
  echo "- Creating role S3ReadWriteRole"
  aws iam create-role --role-name S3ReadWriteRole --assume-role-policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {
      \"Effect\": \"Allow\",
      \"Principal\": {
              \"AWS\": \"arn:aws:iam::$AWS_ACCOUNT_ID:root\"
      } ,
      \"Action\": \"sts:AssumeRole\"
    }
  ]
}" || exit 1
fi

if aws iam get-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList >/dev/null 2>&1; then
  echo "- Policy AfonBucketWhiteList already exists, skipping"
else
  echo "- Creating policy AfonBucketWhiteList"
  aws iam create-policy --policy-name AfonBucketWhiteList  --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {
      \"Sid\": \"AllowBucketEmrysAfonBucketList\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:ListBucket\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucketObjects\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:*\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME/*\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucketPolarisForbidsList\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:ListBucket\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucketPolarisForbidsObjects\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:*\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS/*\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucket2List\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:ListBucket\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME_EXTERNAL\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucket2Objects\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:*\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME_EXTERNAL/*\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucketTrinoList\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:ListBucket\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME_TRINO\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucketTrinoObjects\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:*\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_BUCKET_NAME_TRINO/*\"
    }
  ]
}" || exit 1
fi

if aws iam list-attached-role-policies --role-name S3ReadWriteRole --query "AttachedPolicies[?PolicyName=='AfonBucketWhiteList'].PolicyName" --output text | grep -q AfonBucketWhiteList; then
  echo "- Policy AfonBucketWhiteList already attached to S3ReadWriteRole, skipping"
else
  echo "- Attaching policy AfonBucketWhiteList to S3ReadWriteRole"
  aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn  arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList || exit 1
fi
# This is too coarse grained
#aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess

# Terrafrom assumes the loadblancer policy is provided
if aws iam get-policy --policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy >/dev/null 2>&1; then
  echo "- Policy AWSLoadBalancerControllerIAMPolicy already exists, skipping"
else
  echo "- Creating policy AWSLoadBalancerControllerIAMPolicy"
  curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json || exit 1
  aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy  --policy-document file://iam_policy.json || exit 1
fi

echo "AWS env created."
