TOFU_CLOUD="${CLOUD:-aws}"
TOFU_DIR="$CDKTF_DIR/stacks/$TOFU_CLOUD"
echo "Using tofu dir $TOFU_DIR"
if [ ! -d "$TOFU_DIR" ]; then
  echo "Dir $TOFU_DIR not found.  Execute deploy first."
  usage; exit 0
fi
