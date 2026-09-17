if [ -z "$CDKTF_ENV" ]; then
  echo "Action cdktf-switch requires option --cdktf-env"
  usage; exit 0
fi
CDKTF_ENV_DIR="$CDKTF_DIR-$CDKTF_ENV"
if [ -L "$CDKTF_DIR" ]; then
  rm $CDKTF_DIR
elif [ -d "$CDKTF_DIR" ]; then
  echo "$CDKTF_DIR is not a symlink. Moving $CDKTF_DIR to $CDKTF_ENV_DIR"
  echo "NOTE:  You may have used option --env instead of narrower --cdktf-env."
  mv $CDKTF_DIR $CDKTF_ENV_DIR
fi
ln -s $CDKTF_ENV_DIR $CDKTF_DIR
echo "Switched to cdktf env $CDKTF_ENV"
