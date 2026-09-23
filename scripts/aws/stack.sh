source $AFON_ROOT/scripts/config/afon-source.sh aws

# Checks the state of AWS stack for given env,  ie `tofu destroy` completed: no resources left in the
# stack's tofu state, and no EKS cluster named $CLUSTER_NAME. The state file is checked rather than
# the stack dir, as a completed destroy leaves an empty state (and the dir) behind.
# Exit code: 0 down, 1 deployed, 2 could not verify - never reported as down, so a missing cdktf
# dir or an expired AWS session can't be mistaken for a clean teardown.

STACK_CDKTF_DIR="${CDKTF_DIR:-${AFON_CDKTF:-}}"
STACK_STATE_FILE="$STACK_CDKTF_DIR/terraform.aws.tfstate"
STACK_UP=0

if [ -z "$CLUSTER_NAME" ]; then
  echo "CLUSTER_NAME is not set, cannot verify the stack state"
  exit 2
fi
if [ -z "$STACK_CDKTF_DIR" ] || [ ! -d "$STACK_CDKTF_DIR" ]; then
  echo "cdktf dir '$STACK_CDKTF_DIR' not found (broken symlink?), cannot verify the stack state"
  exit 2
fi
if ! command -v tofu >/dev/null 2>&1; then
  echo "tofu not found, cannot verify the stack"
  exit 2
fi

echo "- Checking the AWS stack state for cluster '$CLUSTER_NAME' (region $AWS_REGION)"
echo "- cdktf dir: $(cd "$STACK_CDKTF_DIR" && pwd -P)"

if [ -f "$STACK_STATE_FILE" ]; then
  STACK_ERR=$(mktemp)
  if ! STACK_REMAINING=$(tofu state list -state="$STACK_STATE_FILE" 2>"$STACK_ERR"); then
    echo "- Could not read tofu state $STACK_STATE_FILE:"
    cat "$STACK_ERR"
    rm -f "$STACK_ERR"
    exit 2
  fi
  rm -f "$STACK_ERR"
  STACK_REMAINING=$(echo "$STACK_REMAINING" | grep -v '^data\.' || true)
  if [ -n "$STACK_REMAINING" ]; then
    echo "- Deployed: $(echo "$STACK_REMAINING" | wc -l | tr -d ' ') resource(s) remain in the tofu state $STACK_STATE_FILE:"
    echo "$STACK_REMAINING" | sed -n '1,10s/^/    /p'
    STACK_UP=1
  else
    echo "- Tofu state is empty"
  fi
else
  echo "- No tofu state file $STACK_STATE_FILE"
fi

STACK_ERR=$(mktemp)
if STACK_CLUSTER_STATUS=$(aws eks describe-cluster --name "$CLUSTER_NAME" --region "$AWS_REGION" --query cluster.status --output text 2>"$STACK_ERR"); then
  echo "- Deployed: EKS cluster '$CLUSTER_NAME' still exists (status $STACK_CLUSTER_STATUS)"
  STACK_UP=1
elif grep -q ResourceNotFoundException "$STACK_ERR"; then
  echo "- No EKS cluster '$CLUSTER_NAME' in $AWS_REGION"
else
  echo "- Could not check for EKS cluster '$CLUSTER_NAME':"
  cat "$STACK_ERR"
  rm -f "$STACK_ERR"
  exit 2
fi
rm -f "$STACK_ERR"

if [ "$STACK_UP" -eq 0 ]; then
  echo "Stack is down."
  exit 0
fi
echo "Stack is deployed."
exit 1
