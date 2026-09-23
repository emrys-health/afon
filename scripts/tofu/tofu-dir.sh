TOFU_CLOUD="$CLOUD"
if [ -z "$TOFU_CLOUD" ] || [ "$TOFU_CLOUD" == "ALL" ]; then
  # afon.sh leaves CLOUD empty by default; afon-cli.sh's default is the literal string "ALL"
  # (its sentinel for "no --cloud given"). Tofu always targets exactly one cloud, so both mean
  # the same thing here: fall back to aws.
  TOFU_CLOUD="aws"
fi
TOFU_DIR="$CDKTF_DIR/stacks/$TOFU_CLOUD"
echo "Using tofu dir $TOFU_DIR"
if [ ! -d "$TOFU_DIR" ]; then
  echo "Dir $TOFU_DIR not found.  Execute deploy first."
  usage; exit 0
fi
