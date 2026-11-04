#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh aws

# AWS has no service accounts, so a user with limited privileges approximates one: it can only assume the S3 role
# (limited to the buckets of the policy AfonBucketWhiteList) and the Glue Iceberg role.
DEFAULT_USERNAME="saemrys"
DEFAULT_PROFILE="default"
ROLE_NAME="S3ReadWriteRoleSA"
BUCKET_POLICY_NAME="AfonBucketWhiteList"
GLUE_ROLE_NAME="GlueIcebergRole"
GLUE_USER_POLICY_NAME="Allow-AssumeRole-GlueIcebergRole"
TAG_KEY="afon-type"
TAG_VALUE="service-user"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [show|list|create|create-key|delete-key] [<username>] [--profile <name>] [--show-key] [--key-id <id>]"
  echo "Sets up the service user of the account AWS_ACCOUNT_ID: an IAM user (default: $DEFAULT_USERNAME) with limited privileges,"
  echo "which assumes the role $ROLE_NAME and the role $GLUE_ROLE_NAME.  Its keys go in secret-aws.properties."
  echo "It must run as a user who can manage IAM, in the account AWS_ACCOUNT_ID, except for show.  Safe to repeat: what exists is skipped."
  echo "  list       lists the service users, ie the IAM users tagged $TAG_KEY=$TAG_VALUE by create; changes nothing"
  echo "  show       (default) shows the user, its policies, the role and the access keys; changes nothing"
  echo "  create     creates the IAM user, unless it exists; creates the role $ROLE_NAME, unless it exists, and attaches to it the policy"
  echo "             $BUCKET_POLICY_NAME; sets the role trust policy to the user; lets the user assume $GLUE_ROLE_NAME"
  echo "  create-key creates an access key for the user, unless it already has one, and prints it, once: put it in secret-aws.properties."
  echo "             With --profile <name> it is also stored, with AWS_REGION, in that profile of ~/.aws (unless it has a key already)"
  echo "  delete-key deletes the access key of the user, which must be the only one, else give it with --key-id <id>"
}

ACTION="show"
case "${1:-}" in
  show|list|create|create-key|delete-key) ACTION="$1"; shift ;;
esac

USERNAME=""
PROFILE=""
KEY_ID_TO_DELETE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --profile)  [ -n "${2:-}" ] || { usage; exit 1; }; PROFILE="$2"; shift ;;
    --show-key) ;; # accepted for compatibility: the key is always printed
    --key-id)   [ -n "${2:-}" ] || { usage; exit 1; }; KEY_ID_TO_DELETE="$2"; shift ;;
    -*)         usage; exit 1 ;;
    *)          [ -z "$USERNAME" ] || { usage; exit 1; }; USERNAME="$1" ;;
  esac
  shift
done
USERNAME="${USERNAME:-$DEFAULT_USERNAME}"

if [ -z "${AWS_ACCOUNT_ID:-}" ]; then
  echo "-- AWS_ACCOUNT_ID is not set - set it in afon-aws.properties"
  exit 1
fi

# The account is the one of the config, not whichever the credentials happen to be for
CALLER_ARN="$(aws sts get-caller-identity --query Arn --output text)" || {
  echo "-- Not logged in to AWS: run afon-cli.sh aws-login"
  exit 1
}
CALLER_ACCOUNT="$(echo "$CALLER_ARN" | cut -d: -f5)"
if [ "$CALLER_ACCOUNT" != "$AWS_ACCOUNT_ID" ]; then
  echo "-- Logged in to AWS account $CALLER_ACCOUNT, not AWS_ACCOUNT_ID $AWS_ACCOUNT_ID"
  exit 1
fi

user_exists() { aws iam get-user --user-name "$USERNAME" >/dev/null 2>&1; }
role_exists() { aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; }
profile_has_key() { aws configure get aws_access_key_id --profile "$PROFILE" >/dev/null 2>&1; }

trust_policy() {
  cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::$AWS_ACCOUNT_ID:user/$USERNAME" },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

glue_assume_role_policy() {
  cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::$AWS_ACCOUNT_ID:role/$GLUE_ROLE_NAME"
    }
  ]
}
EOF
}

list_service_users() {
  echo "- Service users of account $AWS_ACCOUNT_ID (IAM users tagged $TAG_KEY=$TAG_VALUE):"
  FOUND=false
  for U in $(aws iam list-users --query "Users[].UserName" --output text); do
    if [ "$(aws iam list-user-tags --user-name "$U" --query "Tags[?Key=='$TAG_KEY' && Value=='$TAG_VALUE'] | length(@)" --output text)" != "0" ]; then
      echo "    $U"
      FOUND=true
    fi
  done
  if [ "$FOUND" = false ]; then
    echo "    none: create one with: afon-cli.sh aws-service-user create [<username>]"
  fi
}

show_service_user() {
  if ! user_exists; then
    echo "- User $USERNAME does not exist in account $AWS_ACCOUNT_ID: create it with: afon-cli.sh aws-service-user create $USERNAME"
    return 0
  fi
  echo "- User $USERNAME, account $AWS_ACCOUNT_ID"
  echo "- Inline policies of the user:"
  aws iam list-user-policies --user-name "$USERNAME" --query "PolicyNames" --output text | tr '\t' '\n' | sed 's/^/    /'
  if role_exists; then
    echo "- Role $ROLE_NAME, policies attached:"
    aws iam list-attached-role-policies --role-name "$ROLE_NAME" --query "AttachedPolicies[].PolicyName" --output text | tr '\t' '\n' | sed 's/^/    /'
  else
    echo "- Role $ROLE_NAME does not exist"
  fi
  echo "- Access keys (the secret is never shown again):"
  aws iam list-access-keys --user-name "$USERNAME" --query "AccessKeyMetadata[].[AccessKeyId,Status,CreateDate]" --output text | sed 's/^/    /'
  echo "- Access keys last used (date, service, region):"
  for KEY in $(aws iam list-access-keys --user-name "$USERNAME" --query "AccessKeyMetadata[].AccessKeyId" --output text); do
    echo "    $KEY  $(aws iam get-access-key-last-used --access-key-id "$KEY" \
      --query "AccessKeyLastUsed.[LastUsedDate,ServiceName,Region]" --output text)"
  done
  if [ -n "$PROFILE" ]; then
    if profile_has_key; then
      echo "- Profile $PROFILE of ~/.aws has the access key $(aws configure get aws_access_key_id --profile "$PROFILE")"
    else
      echo "- Profile $PROFILE of ~/.aws has no access key"
    fi
  fi
}

create_service_user() {
  if user_exists; then
    echo "- User $USERNAME already exists, skipping"
  else
    echo "- Creating user $USERNAME"
    aws iam create-user --user-name "$USERNAME" --tags Key=created-by,Value=afon >/dev/null || exit 1
  fi
  echo "- Tagging user $USERNAME $TAG_KEY=$TAG_VALUE"
  aws iam tag-user --user-name "$USERNAME" --tags "Key=$TAG_KEY,Value=$TAG_VALUE" || exit 1

  if role_exists; then
    echo "- Role $ROLE_NAME already exists, skipping"
  else
    echo "- Creating role $ROLE_NAME"
    aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$(trust_policy)" >/dev/null || exit 1
  fi

  echo "- Attaching policy $BUCKET_POLICY_NAME to role $ROLE_NAME"
  aws iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn "arn:aws:iam::$AWS_ACCOUNT_ID:policy/$BUCKET_POLICY_NAME" || exit 1

  echo "- Letting user $USERNAME assume role $ROLE_NAME"
  aws iam update-assume-role-policy --role-name "$ROLE_NAME" --policy-document "$(trust_policy)" || exit 1

  # For all the tests to pass
  echo "- Letting user $USERNAME assume role $GLUE_ROLE_NAME"
  aws iam put-user-policy --user-name "$USERNAME" --policy-name "$GLUE_USER_POLICY_NAME" \
    --policy-document "$(glue_assume_role_policy)" || exit 1

  echo "- Next: afon-cli.sh aws-service-user create-key $USERNAME, to create its access key"
}

create_key() {
  if ! user_exists; then
    echo "-- User $USERNAME does not exist: create it with: afon-cli.sh aws-service-user create $USERNAME"
    exit 1
  fi

  KEY_IDS="$(aws iam list-access-keys --user-name "$USERNAME" --query "AccessKeyMetadata[].AccessKeyId" --output text)" || exit 1
  if [ -n "$KEY_IDS" ]; then
    echo "- User $USERNAME already has an access key ($KEY_IDS), skipping: its secret can't be shown again."
    echo "  To get a new one, delete it first: afon-cli.sh aws-service-user delete-key $USERNAME [--key-id <id>]"
    return 0
  fi

  echo "- Creating an access key for $USERNAME"
  read -r KEY_ID KEY_SECRET <<< "$(aws iam create-access-key --user-name "$USERNAME" \
    --query "AccessKey.[AccessKeyId,SecretAccessKey]" --output text)" || exit 1
  if [ -z "$KEY_ID" ]; then
    echo "-- Could not create the access key"
    exit 1
  fi

  # Only into a profile asked for, and never over one that has a key: the credentials in use must not be replaced
  if [ -n "$PROFILE" ]; then
    if profile_has_key; then
      echo "- Profile $PROFILE already has an access key, not replacing it: use another --profile <name>"
    else
      echo "- Storing the key of $USERNAME in the profile $PROFILE of ~/.aws"
      aws configure set aws_access_key_id "$KEY_ID" --profile "$PROFILE" &&
      aws configure set aws_secret_access_key "$KEY_SECRET" --profile "$PROFILE" &&
      aws configure set region "$AWS_REGION" --profile "$PROFILE" || exit 1
      echo "  Use it with:  export AWS_PROFILE=$PROFILE"
    fi
  fi

  echo ""
  echo "The secret is shown only once: put it in secret-aws.properties, do not commit it."
  echo "  AWS_ACCESS_KEY_ID=$KEY_ID"
  echo "  AWS_SECRET_ACCESS_KEY=$KEY_SECRET"
}

delete_key() {
  if ! user_exists; then
    echo "-- User $USERNAME does not exist"
    exit 1
  fi
  KEY_IDS="$(aws iam list-access-keys --user-name "$USERNAME" --query "AccessKeyMetadata[].AccessKeyId" --output text)" || exit 1
  if [ -z "$KEY_IDS" ]; then
    echo "- User $USERNAME has no access key, skipping"
    return 0
  fi
  if [ -z "$KEY_ID_TO_DELETE" ]; then
    if [ "$(echo "$KEY_IDS" | wc -w)" -gt 1 ]; then
      echo "-- User $USERNAME has several access keys ($KEY_IDS): give the one to delete with --key-id <id>"
      exit 1
    fi
    KEY_ID_TO_DELETE="$KEY_IDS"
  elif ! echo " $KEY_IDS " | grep -q " $KEY_ID_TO_DELETE "; then
    echo "-- User $USERNAME has no access key $KEY_ID_TO_DELETE (it has: $KEY_IDS)"
    exit 1
  fi
  echo "- Deleting the access key $KEY_ID_TO_DELETE of $USERNAME"
  aws iam delete-access-key --user-name "$USERNAME" --access-key-id "$KEY_ID_TO_DELETE" || exit 1
  if [ -n "$PROFILE" ] && [ "$(aws configure get aws_access_key_id --profile "$PROFILE" 2>/dev/null)" = "$KEY_ID_TO_DELETE" ]; then
    echo "  The profile $PROFILE of ~/.aws still holds it: remove it from ~/.aws/credentials"
  fi
}

case "$ACTION" in
  show)       show_service_user ;;
  list)       list_service_users ;;
  create)     create_service_user ;;
  create-key) create_key ;;
  delete-key) delete_key ;;
esac
