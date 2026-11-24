#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh aws

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|delete|show>"
  echo "Creates, outside the stack, what signing the Docker images needs on AWS, see docs/stacks.md#image-signing:"
  echo "- the asymmetric KMS key (ECC_NIST_P256, sign and verify) of AWS_COSIGN_KEY, ie awskms:///alias/<name>.  Its key policy lets only the"
  echo "  deployer, ie the IAM principal that runs this, sign with it and read its public key; the account can manage it but not sign."
  echo "- the ECR repositories of AWS_IMAGE_REGISTRY, ie <account>.dkr.ecr.<region>.amazonaws.com[/<path>], one for each image that"
  echo "  docker-image --sign copies to it (docker-image --repositories), with tags immutable, except those of the signatures, and"
  echo "  untagged images expired.  The nodes of the stacks read from them with their node role."
  echo "It is idempotent.  Afterwards run: afon-cli.sh signing-public-key aws"
}

ACTION="${1:-}"
case "$ACTION" in
  create|delete|show) ;;
  *) usage; exit 1 ;;
esac

# The tags of a repository are immutable, so that what was copied and signed cannot be replaced, but for those of the signatures, which cosign
# adds to: sha256-<digest>.sig, as it appends a signature to that tag
SIGNATURE_TAGS='sha256-*.sig'
# Untagged images, ie the manifests that a copy or a signature replaced, are kept for this long
UNTAGGED_DAYS=14
# The window in which a key scheduled for deletion can still be restored, the shortest that KMS allows
KEY_DELETION_DAYS=7

### The registry and the key, from the properties

REGISTRY_HOST="${AWS_IMAGE_REGISTRY:-}"
REGISTRY_HOST="${REGISTRY_HOST%%/*}"
if [[ ! "$REGISTRY_HOST" =~ ^([0-9]{12})\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com$ ]]; then
  echo "-- AWS_IMAGE_REGISTRY must be an ECR registry, ie <account>.dkr.ecr.<region>.amazonaws.com[/<path>], not '${AWS_IMAGE_REGISTRY:-}'"
  exit 1
fi
REGISTRY_ACCOUNT="${BASH_REMATCH[1]}"
REGISTRY_REGION="${BASH_REMATCH[2]}"

# awskms:///alias/<name>, or awskms:///arn:aws:kms:<region>:<account>:alias/<name>, which is how cosign names the key: the other forms, eg its id,
# name a key that this does not make.  Only the ARN says in which region the key is, so cosign needs no AWS_REGION of its own to find it,
# which is what the suggestion in the config has; the key of the other form is in the region of the registry.
KEY_REF="${AWS_COSIGN_KEY:-}"
KEY_REGION="$REGISTRY_REGION"
if [[ "$KEY_REF" =~ ^awskms:///arn:aws:kms:([a-z0-9-]+):([0-9]{12}):(alias/[A-Za-z0-9/_-]+)$ ]]; then
  KEY_REGION="${BASH_REMATCH[1]}"
  if [ "${BASH_REMATCH[2]}" != "$REGISTRY_ACCOUNT" ]; then
    echo "-- The key of AWS_COSIGN_KEY is in the account ${BASH_REMATCH[2]}, not that of the registry, $REGISTRY_ACCOUNT"
    exit 1
  fi
  KEY_ALIAS="${BASH_REMATCH[3]}"
elif [[ "$KEY_REF" =~ ^awskms:///(alias/[A-Za-z0-9/_-]+)$ ]]; then
  KEY_ALIAS="${BASH_REMATCH[1]}"
else
  echo "-- AWS_COSIGN_KEY must be awskms:///alias/<name>, or awskms:///arn:aws:kms:<region>:<account>:alias/<name>, not '$KEY_REF'"
  exit 1
fi

CALLER_ARN="$(aws sts get-caller-identity --query Arn --output text)" || exit 1
CALLER_ACCOUNT="$(aws sts get-caller-identity --query Account --output text)" || exit 1
if [ "$CALLER_ACCOUNT" != "$REGISTRY_ACCOUNT" ]; then
  echo "-- Logged in to the account $CALLER_ACCOUNT, but AWS_IMAGE_REGISTRY is that of the account $REGISTRY_ACCOUNT"
  exit 1
fi

# The IAM principal that signs: the one logged in, as a user, or, for a role that is assumed, the role, not its session, since
# a key policy names the role.  Looked up, as the ARN of the role has its path, which that of the session leaves out.
deployer_arn() {
  case "$CALLER_ARN" in
    arn:*:sts::*:assumed-role/*)
      local ROLE="${CALLER_ARN#*:assumed-role/}"
      ROLE="${ROLE%%/*}"
      aws iam get-role --role-name "$ROLE" --query Role.Arn --output text
      ;;
    arn:*:iam::*:user/*) echo "$CALLER_ARN" ;;
    *) echo "-- Cannot sign as '$CALLER_ARN': use an IAM user or a role" >&2; return 1 ;;
  esac
}

# The repositories of the registry that the images are copied to, from the images file, one for each image that is mirrored
afon() {
  local command="$1"
  shift
  if [ -x "$AFON_ROOT/afon.sh" ]; then
    "$AFON_ROOT/afon.sh" -a "$command" -- "$@"
  else
    SPRING_MAIN_BANNER_MODE="${SPRING_MAIN_BANNER_MODE:-off}" "${AFON_CLI_DIR:-$AFON_ROOT}/afon-cli.sh" "$command" "$@"
  fi
}
repositories() {
  local NAMES
  NAMES="$(afon docker-image --repositories --cloud aws)" || { echo "-- Cannot list the repositories of the images" >&2; return 1; }
  # only the names, if the launcher printed anything else
  printf '%s\n' "$NAMES" | grep -E '^[a-z0-9][a-z0-9._/-]*$'
  return 0
}

# The id of the key of the alias, or nothing if there is none.  Never fails: the launcher runs scripts with -e, which would end this one, with no
# message, at the assignment of a failed command, ie when the key is not there yet
key_id() {
  aws kms describe-key --key-id "$KEY_ALIAS" --region "$KEY_REGION" --query KeyMetadata.KeyId --output text 2>/dev/null || true
}

case "$ACTION" in
  create)
    DEPLOYER_ARN="$(deployer_arn)" || exit 1
    WORK_DIR="$(mktemp -d)"
    trap 'rm -rf "$WORK_DIR"' EXIT

    ## Key

    # Sign, verify and read the public key are only for the deployer.  The account, ie the IAM policies of its principals, is delegated the
    # management of the key but not these, nor the creation of grants, which could give them: so no other role can sign.  Whoever
    # can change this policy can still give itself the right to, which is the case of any key, so who may is for IAM to restrict.
    cat > "$WORK_DIR/key-policy.json" <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowTheDeployerToSign",
      "Effect": "Allow",
      "Principal": {"AWS": "$DEPLOYER_ARN"},
      "Action": ["kms:Sign", "kms:Verify", "kms:GetPublicKey", "kms:DescribeKey"],
      "Resource": "*"
    },
    {
      "Sid": "AllowTheAccountToManageTheKey",
      "Effect": "Allow",
      "Principal": {"AWS": "arn:aws:iam::$CALLER_ACCOUNT:root"},
      "Action": ["kms:DescribeKey", "kms:GetKeyPolicy", "kms:PutKeyPolicy", "kms:ListKeyPolicies", "kms:ListResourceTags",
                 "kms:CreateAlias", "kms:UpdateAlias", "kms:DeleteAlias", "kms:EnableKey", "kms:DisableKey",
                 "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion", "kms:TagResource", "kms:UntagResource"],
      "Resource": "*"
    }
  ]
}
POLICY
    KEY_ID="$(key_id)"
    if [ -n "$KEY_ID" ]; then
      echo "- Key '$KEY_ALIAS' already exists ($KEY_ID), skipping creation"
    else
      echo "- Creating the ECC_NIST_P256 key for '$KEY_ALIAS' in $KEY_REGION"
      KEY_ID="$(aws kms create-key --region "$KEY_REGION" --key-spec ECC_NIST_P256 --key-usage SIGN_VERIFY \
        --description "Afon: signs the Docker images (cosign), $KEY_ALIAS" --policy "file://$WORK_DIR/key-policy.json" \
        --tags TagKey=afon,TagValue=image-signing --query KeyMetadata.KeyId --output text)" || exit 1
      aws kms create-alias --region "$KEY_REGION" --alias-name "$KEY_ALIAS" --target-key-id "$KEY_ID" || exit 1
    fi
    # Also for a key that exists, so that the deployer that runs this is the one that signs, as the policy of the key it made may be another's
    echo "- Letting '$DEPLOYER_ARN' sign with the key"
    aws kms put-key-policy --region "$KEY_REGION" --key-id "$KEY_ID" --policy-name default --policy "file://$WORK_DIR/key-policy.json" || exit 1

    ## Registry

    cat > "$WORK_DIR/lifecycle.json" <<LIFECYCLE
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "Expire untagged images after $UNTAGGED_DAYS days",
      "selection": {"tagStatus": "untagged", "countType": "sinceImagePushed", "countUnit": "days", "countNumber": $UNTAGGED_DAYS},
      "action": {"type": "expire"}
    }
  ]
}
LIFECYCLE
    REPOSITORIES="$(repositories)" || exit 1
    while IFS= read -r REPOSITORY; do
      [ -n "$REPOSITORY" ] || continue
      if aws ecr describe-repositories --region "$REGISTRY_REGION" --repository-names "$REPOSITORY" --output json >/dev/null 2>&1; then
        echo "- Repository '$REPOSITORY' already exists, setting it as below"
        aws ecr put-image-tag-mutability --region "$REGISTRY_REGION" --repository-name "$REPOSITORY" \
          --image-tag-mutability IMMUTABLE_WITH_EXCLUSION \
          --image-tag-mutability-exclusion-filters "filterType=WILDCARD,filter=$SIGNATURE_TAGS" --output json >/dev/null || exit 1
      else
        echo "- Creating repository '$REPOSITORY'"
        aws ecr create-repository --region "$REGISTRY_REGION" --repository-name "$REPOSITORY" \
          --image-tag-mutability IMMUTABLE_WITH_EXCLUSION \
          --image-tag-mutability-exclusion-filters "filterType=WILDCARD,filter=$SIGNATURE_TAGS" \
          --image-scanning-configuration scanOnPush=true --tags Key=afon,Value=image-signing --output json >/dev/null || exit 1
      fi
      aws ecr put-lifecycle-policy --region "$REGISTRY_REGION" --repository-name "$REPOSITORY" \
        --lifecycle-policy-text "file://$WORK_DIR/lifecycle.json" --output json >/dev/null || exit 1
    done <<< "$REPOSITORIES"

    echo "- Registry '$REGISTRY_HOST' and key '$KEY_REF' ready."
    echo "- Next: afon-cli.sh signing-public-key aws, then docker-image --sign --cloud aws"
    ;;

  delete)
    REPOSITORIES="$(repositories)" || exit 1
    while IFS= read -r REPOSITORY; do
      [ -n "$REPOSITORY" ] || continue
      if aws ecr describe-repositories --region "$REGISTRY_REGION" --repository-names "$REPOSITORY" --output json >/dev/null 2>&1; then
        echo "- Deleting repository '$REPOSITORY', and the images in it"
        aws ecr delete-repository --region "$REGISTRY_REGION" --repository-name "$REPOSITORY" --force --output json >/dev/null || exit 1
      else
        echo "- Repository '$REPOSITORY' does not exist, skipping"
      fi
    done <<< "$REPOSITORIES"
    KEY_ID="$(key_id)"
    if [ -n "$KEY_ID" ]; then
      echo "- Deleting the alias '$KEY_ALIAS', and scheduling the deletion of the key $KEY_ID in $KEY_DELETION_DAYS days"
      aws kms delete-alias --region "$KEY_REGION" --alias-name "$KEY_ALIAS" || exit 1
      aws kms schedule-key-deletion --region "$KEY_REGION" --key-id "$KEY_ID" --pending-window-in-days $KEY_DELETION_DAYS --output json >/dev/null || exit 1
      echo "- Until then it can be restored: aws kms cancel-key-deletion --region $KEY_REGION --key-id $KEY_ID"
    else
      echo "- Key '$KEY_ALIAS' does not exist, skipping"
    fi
    ;;

  show)
    KEY_ID="$(key_id)"
    if [ -n "$KEY_ID" ]; then
      echo "- Key '$KEY_ALIAS' is $KEY_ID:"
      aws kms describe-key --region "$KEY_REGION" --key-id "$KEY_ID" \
        --query "KeyMetadata.{state:KeyState,spec:KeySpec,usage:KeyUsage}" --output json
      echo "- It may be signed with by:"
      aws kms get-key-policy --region "$KEY_REGION" --key-id "$KEY_ID" --policy-name default --query Policy --output text 2>/dev/null \
        | jq -r '.Statement[] | select(.Sid == "AllowTheDeployerToSign") | .Principal.AWS' \
        || echo "  (cannot read the key policy)"
    else
      echo "- Key '$KEY_ALIAS' does not exist, or cannot be read"
    fi
    REPOSITORIES="$(repositories)" || exit 1
    while IFS= read -r REPOSITORY; do
      [ -n "$REPOSITORY" ] || continue
      if aws ecr describe-repositories --region "$REGISTRY_REGION" --repository-names "$REPOSITORY" --output json >/dev/null 2>&1; then
        echo "- Repository '$REPOSITORY' exists"
      else
        echo "- Repository '$REPOSITORY' does not exist"
      fi
    done <<< "$REPOSITORIES"
    ;;
esac
