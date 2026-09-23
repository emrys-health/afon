source $AFON_ROOT/scripts/tofu/tofu-dir.sh
TOFU_CMD="tofu -chdir=$TOFU_DIR destroy $* -auto-approve"
echo "Executing: $TOFU_CMD"
eval "$TOFU_CMD"
