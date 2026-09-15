source $AFON_ROOT/scripts/config/afon-source.sh aws

IFS=',' read -ra CATALOG_IDS <<< "$AWS_CATALOG_NAMES"

# Derives an IAM statement Sid fragment from a catalog id, eg "aws_forbidden" -> "AwsForbidden".
sid_of() {
  echo "$1" | awk -F'_' '{ for (i=1; i<=NF; i++) { $i = toupper(substr($i,1,1)) substr($i,2) } print }' OFS=''
}

# Reads AWS_BUCKET_NAME_<id>, echoing "" instead of erroring under `set -u` if that catalog (eg a
# custom type with no bucket of its own) never defined one.
bucket_of() {
  local VAR="AWS_BUCKET_NAME_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  fi
}

# Reads AWS_ADMIN_GRANTS_<id>, echoing "true" if unset (matching AfonCatalogs' own default).
admin_grants_of() {
  local VAR="AWS_ADMIN_GRANTS_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  else
    echo "true"
  fi
}

create_bucket_if_absent() {
  local BUCKET="$1"
  if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
    echo "- Bucket $BUCKET already exists, skipping"
  else
    echo "- Creating bucket $BUCKET"
    aws s3api create-bucket --bucket "$BUCKET" --region $AWS_REGION --create-bucket-configuration LocationConstraint=$AWS_REGION || exit 1
  fi
}

# Creates every catalog's bucket (from AWS_CATALOG_NAMES); every catalog with
# AWS_ADMIN_GRANTS_<id>=true also gets an IAM whitelist entry, so a new catalog needs no change
# here - only an entry in catalogs-aws.properties. aws_forbidden's bucket is still created, but
# excluded from the whitelist (matching azure/create.sh and gcp/create.sh's own grant loops) -
# a separate, explicit IAM Deny statement is what actually protects it, so this exclusion is
# redundant-but-safe defense in depth alongside that Deny, not a substitute for it.
STATEMENTS=""
for ID in "${CATALOG_IDS[@]}"; do
  BUCKET="$(bucket_of "$ID")"
  if [ -z "$BUCKET" ]; then
    continue
  fi
  create_bucket_if_absent "$BUCKET"
  if [ "$(admin_grants_of "$ID")" != "true" ]; then
    continue
  fi
  SID=$(sid_of "$ID")
  if [ -n "$STATEMENTS" ]; then STATEMENTS="$STATEMENTS,"; fi
  STATEMENTS="$STATEMENTS
    {
      \"Sid\": \"AllowBucket${SID}List\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:ListBucket\",
      \"Resource\": \"arn:aws:s3:::$BUCKET\"
    },
    {
      \"Sid\": \"AllowBucket${SID}Objects\",
      \"Effect\": \"Allow\",
      \"Action\": \"s3:*\",
      \"Resource\": \"arn:aws:s3:::$BUCKET/*\"
    }"
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
  \"Statement\": [$STATEMENTS
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
