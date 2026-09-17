if [ -d $CDKTF_DIR ]; then
  if [ -f "$CDKTF_DIR/manifest.json" ]; then
    cat $CDKTF_DIR/manifest.json
  fi
  ls -lt $CDKTF_DIR
  ls -lt $CDKTF_DIR/*
else
  echo "$CDKTF_DIR not found."
fi
