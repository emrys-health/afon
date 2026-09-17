if [ -d $CONFIG_DIR ]; then
  if [ -f "$CONFIG_DIR/afon.md" ]; then
    cat $CONFIG_DIR/afon.md
  fi
  ls -lt $CONFIG_DIR
  ls -lt $CONFIG_DIR/*
else
  echo "$CONFIG_DIR not found."
fi
