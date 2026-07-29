# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && source afon-aws.properties && set +a

aws s3api create-bucket --bucket $AWS_S3_EXTERNAL_BUCKET_NAME   --region $AWS_REGION --create-bucket-configuration LocationConstraint=$AWS_REGION && \
aws s3api create-bucket --bucket $AWS_S3_BUCKET_NAME_TRINO      --region $AWS_REGION --create-bucket-configuration LocationConstraint=$AWS_REGION && \
aws s3api create-bucket --bucket $AWS_S3_BUCKET_NAME            --region $AWS_REGION --create-bucket-configuration LocationConstraint=$AWS_REGION && \
aws s3api create-bucket --bucket $AWS_S3_BUCKET_NAME_FORBIDDEN  --region $AWS_REGION --create-bucket-configuration LocationConstraint=$AWS_REGION && \
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
}" && \
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
      \"Sid\": \"AllowBucketEmrysAfonBucket2List\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:ListBucket\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_EXTERNAL_BUCKET_NAME\"
    },
    {
      \"Sid\": \"AllowBucketEmrysAfonBucket2Objects\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:*\",
      \"Resource\": \"arn:aws:s3:::$AWS_S3_EXTERNAL_BUCKET_NAME/*\"
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
}" && \
aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn  arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList
# This is too coarse grained
#aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess

# Terrafrom assumes the loadblancer policy is provided
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json
aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy  --policy-document file://iam_policy.json