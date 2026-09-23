source $AFON_ROOT/scripts/tofu/tofu-dir.sh
TOFU_CMD="tofu -chdir=$TOFU_DIR state $*"
echo "Executing: $TOFU_CMD"
eval "$TOFU_CMD"
