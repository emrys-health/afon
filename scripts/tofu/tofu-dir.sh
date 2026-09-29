# Sourced by the tofu scripts, whose args ($@) are passed on to tofu, after the optional stack id, eg:
#   afon-cli.sh tofu-plan aws-polaris -target=...
# Each stack has its own dir stacks/<cloud>-<stack-type> and tofu state terraform.<cloud>-<stack-type>.tfstate,
# eg stacks/aws-keycloak, or the legacy stacks/<cloud> and terraform.<cloud>.tfstate shared by all the stacks
# of the cloud.  If the first arg is the id of a stack with a dir, it is shifted off and tofu runs in that dir.
# Else tofu runs in the dir of the only stack of the cloud with a tofu state, else in the only dir of the cloud,
# eg a stack synthesised but not yet applied.
TOFU_DIR=
if [ -n "${1:-}" ] && [[ "$1" != -* ]] && [ -d "$CDKTF_DIR/stacks/$1" ]; then
  TOFU_DIR="$CDKTF_DIR/stacks/$1"
  shift
fi
TOFU_CLOUD="$CLOUD"
if [ -z "$TOFU_CLOUD" ] || [ "$TOFU_CLOUD" == "ALL" ]; then
  # afon.sh leaves CLOUD empty by default; afon-cli.sh's default is the literal string "ALL"
  # (its sentinel for "no --cloud given"). Tofu always targets exactly one cloud, so both mean
  # the same thing here: fall back to aws.
  TOFU_CLOUD="aws"
fi
if [ -z "$TOFU_DIR" ]; then
  STACK_DIRS=
  DEPLOYED_DIRS=
  for STACK_DIR in "$CDKTF_DIR/stacks/$TOFU_CLOUD" "$CDKTF_DIR/stacks/$TOFU_CLOUD"-*; do
    if [ -d "$STACK_DIR" ]; then
      STACK_DIRS="$STACK_DIRS $STACK_DIR"
      if [ -f "$CDKTF_DIR/terraform.$(basename "$STACK_DIR").tfstate" ]; then
        DEPLOYED_DIRS="$DEPLOYED_DIRS $STACK_DIR"
      fi
    fi
  done
  # not using `set --` to count the dirs, as the sourcing script passes its own args ($@) on to tofu
  for CANDIDATE_DIRS in "$DEPLOYED_DIRS" "$STACK_DIRS"; do
    CANDIDATE_COUNT=$(echo $CANDIDATE_DIRS | wc -w | tr -d ' ')
    if [ "$CANDIDATE_COUNT" -eq 1 ]; then
      TOFU_DIR=$(echo $CANDIDATE_DIRS); break
    elif [ "$CANDIDATE_COUNT" -gt 1 ]; then
      echo "Found more than one stack dir in cloud $TOFU_CLOUD:$CANDIDATE_DIRS"
      echo "Give the stack id as first arg, eg: afon-cli.sh tofu-plan $(basename $(echo $CANDIDATE_DIRS | cut -d' ' -f1))"
      usage; exit 0
    fi
  done
fi
if [ -z "$TOFU_DIR" ]; then
  echo "No stack dir found in $CDKTF_DIR/stacks for cloud $TOFU_CLOUD.  Execute deploy first."
  usage; exit 0
fi
echo "Using tofu dir $TOFU_DIR"
