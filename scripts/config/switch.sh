#!/usr/bin/env bash
if [ -z "$CONFIG_ENV" ]; then
  echo "Action config-switch requires option --config-env"
  usage; exit 0
fi
CONFIG_ENV_DIR="$CONFIG_DIR-$CONFIG_ENV"
# Nothing is said, nor done, if the config is already that of the env, as when the commands run by a command do not switch again.
if [ -L "$CONFIG_DIR" ] && [ "$(readlink "$CONFIG_DIR")" == "$CONFIG_ENV_DIR" ]; then
  :
else
  if [ -L "$CONFIG_DIR" ]; then
    rm $CONFIG_DIR
  elif [ -d "$CONFIG_DIR" ]; then
    echo "$CONFIG_DIR is not a symlink. Moving $CONFIG_DIR to $CONFIG_ENV_DIR"
    echo "NOTE:  You may have used option --env instead of narrower --config-env."
    mv $CONFIG_DIR $CONFIG_ENV_DIR
  fi
  ln -s $CONFIG_ENV_DIR $CONFIG_DIR
  echo "Switched to config env $CONFIG_ENV"
fi
