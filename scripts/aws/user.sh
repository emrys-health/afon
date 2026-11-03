source $AFON_ROOT/scripts/config/afon-source.sh aws

DEFAULT_GROUP="afon-deployers"
DEFAULT_PROFILE="default"
# Broad: see "Stack deployer" in roles.md, which says what is needed, to narrow them for anything but a development account
DEFAULT_POLICIES=("arn:aws:iam::aws:policy/PowerUserAccess" "arn:aws:iam::aws:policy/IAMFullAccess")

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [show|create|create-key|delete-key] [<username>] [--group <name>] [--policy-arn <arn>]... [--profile <name>] [--show-key] [--key-id <id>]"
  echo "Sets up the AWS credentials of a user of the account AWS_ACCOUNT_ID who deploys the stacks, see roles.md."
  echo "It must run as a user who can manage IAM, in the account AWS_ACCOUNT_ID, except for show.  Safe to repeat: what exists is skipped."
  echo "  show       (default) shows the user, its groups, policies and access keys, and whether the profile has a key; changes nothing."
  echo "             Without <username>, shows the user logged in."
  echo "  create     creates the IAM user, unless it exists; creates the IAM group (default: $DEFAULT_GROUP), unless it exists, with"
  echo "             the policies given by --policy-arn, repeated (default: PowerUserAccess and IAMFullAccess), which are only attached"
  echo "             when it is created; and adds the user to it"
  echo "  create-key creates an access key for the user, unless it already has one, and stores it, with AWS_REGION, in the profile"
  echo "             --profile (default: $DEFAULT_PROFILE) of ~/.aws/credentials and ~/.aws/config, with aws configure set, unless the"
  echo "             profile has a key already, in which case the key is only shown.  --show-key also prints the key, once,"
  echo "             eg to hand it over"
  echo "  delete-key deletes the access key of the user, which must be the only one, else give it with --key-id <id>.  The profile"
  echo "             of ~/.aws that holds it is not changed: edit ~/.aws/credentials to remove it"
}

ACTION="show"
case "${1:-}" in
  show|create|create-key|delete-key) ACTION="$1"; shift ;;
esac

USERNAME=""
GROUP="$DEFAULT_GROUP"
POLICIES=()
PROFILE="$DEFAULT_PROFILE"
SHOW_KEY=false
KEY_ID_TO_DELETE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --group)      [ -n "${2:-}" ] || { usage; exit 1; }; GROUP="$2"; shift ;;
    --policy-arn) [ -n "${2:-}" ] || { usage; exit 1; }; POLICIES+=("$2"); shift ;;
    --profile)    [ -n "${2:-}" ] || { usage; exit 1; }; PROFILE="$2"; shift ;;
    --show-key)   SHOW_KEY=true ;;
    --key-id)     [ -n "${2:-}" ] || { usage; exit 1; }; KEY_ID_TO_DELETE="$2"; shift ;;
    -*)           usage; exit 1 ;;
    *)            [ -z "$USERNAME" ] || { usage; exit 1; }; USERNAME="$1" ;;
  esac
  shift
done
if [ ${#POLICIES[@]} -eq 0 ]; then
  POLICIES=("${DEFAULT_POLICIES[@]}")
fi

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

if [ -z "$USERNAME" ]; then
  if [ "$ACTION" != "show" ]; then
    usage
    exit 1
  fi
  case "$CALLER_ARN" in
    *:user/*) USERNAME="${CALLER_ARN##*/}" ;;
    *) echo "-- Logged in as $CALLER_ARN, which is not an IAM user: give <username>"; exit 1 ;;
  esac
fi

user_exists() { aws iam get-user --user-name "$USERNAME" >/dev/null 2>&1; }
profile_has_key() { aws configure get aws_access_key_id --profile "$PROFILE" >/dev/null 2>&1; }

show_user() {
  if ! user_exists; then
    echo "- User $USERNAME does not exist in account $AWS_ACCOUNT_ID: create it with: afon-cli.sh aws-user create $USERNAME"
    return 0
  fi
  echo "- User $USERNAME, account $AWS_ACCOUNT_ID"
  echo "- Groups:"
  aws iam list-groups-for-user --user-name "$USERNAME" --query "Groups[].GroupName" --output text | tr '\t' '\n' | sed 's/^/    /'
  echo "- Policies attached to the user:"
  aws iam list-attached-user-policies --user-name "$USERNAME" --query "AttachedPolicies[].PolicyName" --output text | tr '\t' '\n' | sed 's/^/    /'
  for G in $(aws iam list-groups-for-user --user-name "$USERNAME" --query "Groups[].GroupName" --output text); do
    echo "- Policies attached to the group $G:"
    aws iam list-attached-group-policies --group-name "$G" --query "AttachedPolicies[].PolicyName" --output text | tr '\t' '\n' | sed 's/^/    /'
  done
  echo "- Access keys (the secret is never shown again):"
  aws iam list-access-keys --user-name "$USERNAME" --query "AccessKeyMetadata[].[AccessKeyId,Status,CreateDate]" --output text | sed 's/^/    /'
  if profile_has_key; then
    echo "- Profile $PROFILE of ~/.aws has the access key $(aws configure get aws_access_key_id --profile "$PROFILE")"
  else
    echo "- Profile $PROFILE of ~/.aws has no access key"
  fi
}

create_user() {
  if user_exists; then
    echo "- User $USERNAME already exists, skipping"
  else
    echo "- Creating user $USERNAME"
    aws iam create-user --user-name "$USERNAME" --tags Key=created-by,Value=afon >/dev/null || exit 1
  fi

  if aws iam get-group --group-name "$GROUP" >/dev/null 2>&1; then
    echo "- Group $GROUP already exists, skipping its policies"
  else
    echo "- Creating group $GROUP"
    aws iam create-group --group-name "$GROUP" >/dev/null || exit 1
    for POLICY in "${POLICIES[@]}"; do
      echo "- Attaching policy $POLICY to group $GROUP"
      aws iam attach-group-policy --group-name "$GROUP" --policy-arn "$POLICY" || exit 1
    done
  fi

  if aws iam get-group --group-name "$GROUP" --query "Users[?UserName=='$USERNAME'].UserName" --output text | grep -q "$USERNAME"; then
    echo "- User $USERNAME is already in group $GROUP, skipping"
  else
    echo "- Adding user $USERNAME to group $GROUP"
    aws iam add-user-to-group --group-name "$GROUP" --user-name "$USERNAME" || exit 1
  fi
  echo "- Next: afon-cli.sh aws-user create-key $USERNAME, to create its access key"
}

create_key() {
  if ! user_exists; then
    echo "-- User $USERNAME does not exist: create it with: afon-cli.sh aws-user create $USERNAME"
    exit 1
  fi

  KEY_IDS="$(aws iam list-access-keys --user-name "$USERNAME" --query "AccessKeyMetadata[].AccessKeyId" --output text)" || exit 1
  if [ -n "$KEY_IDS" ]; then
    echo "- User $USERNAME already has an access key ($KEY_IDS), skipping: its secret can't be shown again."
    echo "  To get a new one, delete it first: afon-cli.sh aws-user delete-key $USERNAME [--key-id <id>]"
    return 0
  fi

  echo "- Creating an access key for $USERNAME"
  read -r KEY_ID KEY_SECRET <<< "$(aws iam create-access-key --user-name "$USERNAME" \
    --query "AccessKey.[AccessKeyId,SecretAccessKey]" --output text)" || exit 1
  if [ -z "$KEY_ID" ]; then
    echo "-- Could not create the access key"
    exit 1
  fi

  # Into the profile of ~/.aws, which is the one of whoever runs this: a profile that has a key is left alone, so that
  # the credentials in use are not replaced by those of another user, eg when run by an admin to set up someone else
  if profile_has_key; then
    echo "- Profile $PROFILE already has an access key, not replacing it: use --profile <name> for another profile"
    SHOW_KEY=true
  else
    echo "- Storing the key of $USERNAME in the profile $PROFILE of ~/.aws"
    aws configure set aws_access_key_id "$KEY_ID" --profile "$PROFILE" &&
    aws configure set aws_secret_access_key "$KEY_SECRET" --profile "$PROFILE" &&
    aws configure set region "$AWS_REGION" --profile "$PROFILE" || exit 1
    echo "  Use it with:  export AWS_PROFILE=$PROFILE"
  fi

  if [ "$SHOW_KEY" = true ]; then
    echo ""
    echo "The secret is shown only once: hand it over securely, do not commit it."
    echo "  AWS_ACCESS_KEY_ID=$KEY_ID"
    echo "  AWS_SECRET_ACCESS_KEY=$KEY_SECRET"
  fi
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
  if [ "$(aws configure get aws_access_key_id --profile "$PROFILE" 2>/dev/null)" = "$KEY_ID_TO_DELETE" ]; then
    echo "  The profile $PROFILE of ~/.aws still holds it: remove it from ~/.aws/credentials"
  fi
}

case "$ACTION" in
  show)       show_user ;;
  create)     create_user ;;
  create-key) create_key ;;
  delete-key) delete_key ;;
esac
