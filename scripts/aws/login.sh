source $AFON_ROOT/scripts/config/afon-source.sh aws

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [--force] [--profile <name>]"
  echo "Logs in to AWS with aws login, unless the current credentials are already those of AWS_ACCOUNT_ID."
  echo "  --force           log in even if already logged in to AWS_ACCOUNT_ID, eg as another user"
  echo "  --profile <name>  check and log in under that profile instead of the default one.  Use it when the default"
  echo "                    profile has access keys, which aws login won't replace.  Then export AWS_PROFILE=<name>"
  echo "                    in your shell for the other commands to use it."
}

FORCE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --force)   FORCE=true ;;
    --profile) [ -n "${2:-}" ] || { usage; exit 1; }; export AWS_PROFILE="$2"; shift ;;
    *)         usage; exit 1 ;;
  esac
  shift
done

# Logs in only if the current credentials are not already those of AWS_ACCOUNT_ID, so that this is safe to repeat and
# works with credentials that aws login can't replace, eg access keys in the default profile or an instance profile.
CALLER_ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
if [ "$FORCE" = false ] && [ -n "${AWS_ACCOUNT_ID:-}" ] && [ "$CALLER_ACCOUNT" = "$AWS_ACCOUNT_ID" ]; then
  echo "- Already logged in to AWS account $AWS_ACCOUNT_ID, skipping login (--force to log in again)"
else
  if [ "$FORCE" = false ] && [ -n "$CALLER_ACCOUNT" ]; then
    echo "- Logged in to AWS account $CALLER_ACCOUNT, not AWS_ACCOUNT_ID '${AWS_ACCOUNT_ID:-}'"
  fi
  echo "aws login${AWS_PROFILE:+ --profile $AWS_PROFILE}"
  aws login || exit 1
fi

# Shows who is logged in, so that no other step is needed
source $AFON_ROOT/scripts/aws/account.sh
