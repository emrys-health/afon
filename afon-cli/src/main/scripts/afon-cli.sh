#!/usr/bin/env bash
# Runs the afon-cli executable jar with the JVM flags afon requires
# Looks for the jar in the dir specified by the env $AFON_CLI_DIR,
# falling back on the script dir.
set -euo pipefail

usage(){
  echo "Usage: $0 [--home <dir>] [--cdktf <dir>] [--cloud|-c <cloud>]"
  echo "          [--config-env <env>] [--cdktf-env <env>] [--env|-e <env>]"
  echo "          [--logging|-l <level>] [--logging-console|-lc <level>]"
  echo "          <command> [args...]"
  echo "options, settable via the flags above or the equivalent AFON_* env var (a flag wins over its env var):"
  echo "  --home / AFON_HOME:                       the afon home dir.  Defaults to '$HOME/.afon'."
  echo "  --cdktf / AFON_CDKTF:                     the afon cdktf dir.  Defaults to 'AFON_HOME/cdktf'."
  echo "  --cloud / AFON_CLOUD:                     the cloud to target.  One of aws|azure|gcp.  Defaults to 'ALL'."
  echo "  --config-env / AFON_CONFIG_ENV:           the config env name.  Switches AFON_HOME/config to 'config-<env>' immediately, before running any command."
  echo "  --cdktf-env / AFON_CDKTF_ENV:             the cdktf env name.  Switches AFON_CDKTF to 'AFON_CDKTF-<env>' immediately, before running any command."
  echo "  --env / AFON_ENV:                         sets both --cdktf-env and --config-env.  Overrides them if also set."
  echo "  --logging / AFON_LOGGING:                 the file logging level.  Defaults to 'INFO'.  The file logs are found in 'AFON_HOME/logs'."
  echo "  --logging-console / AFON_LOGGING_CONSOLE: the console logging level.  Defaults to 'OFF'."
  echo "  AFON_CLI_DIR (env only):                  the cli distribution dir this script lives in.  Auto-detected."
  echo "  AFON_ENVS (env only):                     NAME=VALUE pairs of system properties passed to the CLI java process."
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_CLI_DIR="${AFON_CLI_DIR:-"$SCRIPT_DIR"}"
if [ "$AFON_CLI_DIR" == "$SCRIPT_DIR" ] && [[ "$SCRIPT_DIR" == *"afon-cli/src/main/script"* ]]; then
  echo "AFON_CLI_DIR not set.  Looking for afon-cli jar in mvn target dir."
  AFON_CLI_DIR="$SCRIPT_DIR/../../../target"
fi
JAR=""
if [ -z "$JAR" ]; then
    JAR="$(ls $AFON_CLI_DIR/afon-cli*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
fi
if [ -z "$JAR" ]; then
    echo "The afon-cli jar not found in the $AFON_CLI_DIR." >&2
    exit 1
fi

AFON_HOME="${AFON_HOME:-}"
AFON_CDKTF="${AFON_CDKTF:-}"
AFON_CLOUD="${AFON_CLOUD:-}"
AFON_LOGGING="${AFON_LOGGING:-}"
AFON_LOGGING_CONSOLE="${AFON_LOGGING_CONSOLE:-}"
AFON_CONFIG_ENV="${AFON_CONFIG_ENV:-}"
AFON_CDKTF_ENV="${AFON_CDKTF_ENV:-}"
AFON_ENV="${AFON_ENV:-}"
AFON_ENVS="${AFON_ENVS:-}"

ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)
      AFON_HOME="$2"; shift 2 ;;
    --cdktf)
      AFON_CDKTF="$2"; shift 2 ;;
    --cloud|-c)
      AFON_CLOUD="$2"; shift 2 ;;
    --config-env)
      AFON_CONFIG_ENV="$2"; shift 2 ;;
    --cdktf-env)
      AFON_CDKTF_ENV="$2"; shift 2 ;;
    --env|-e)
      AFON_ENV="$2"; shift 2 ;;
    --logging|-l)
      AFON_LOGGING="$2"; shift 2 ;;
    --logging-console|-lc)
      AFON_LOGGING_CONSOLE="$2"; shift 2 ;;
    --help|-h)
      # Only show this script's own usage when no command has been given yet; once a command
      # (eg "azure") is in ARGS, forward -h/--help so the java side can handle it - eg to list
      # every command under that prefix.
      if [ ${#ARGS[@]} -eq 0 ]; then
        usage; exit 0
      fi
      ARGS+=("$1"); shift ;;
    *)
      ARGS+=("$1"); shift ;;
  esac
done
# macOS ships bash 3.2, which throws "unbound variable" expanding "${ARGS[@]}" under set -u
# when ARGS is empty (fixed in bash 4.4+), so guard the expansion instead of using it directly.
if [ ${#ARGS[@]} -gt 0 ]; then
  set -- "${ARGS[@]}"
else
  set --
fi

if [ -n "$AFON_ENV" ]; then
  echo "Using env=$AFON_ENV"
  echo "Both cdktf and config env are set to $AFON_ENV"
  AFON_CDKTF_ENV="$AFON_ENV"
  AFON_CONFIG_ENV="$AFON_ENV"
fi

AFON_HOME="${AFON_HOME:-$HOME/.afon}"
AFON_CDKTF="${AFON_CDKTF:-$AFON_HOME/cdktf}"
AFON_CLOUD="${AFON_CLOUD:-ALL}"
AFON_LOGGING="${AFON_LOGGING:-INFO}"
AFON_LOGGING_CONSOLE="${AFON_LOGGING_CONSOLE:-OFF}"

# CONFIG_ENV/CDKTF_ENV are global: if set, apply the switch commands upstream via afon-cli-scripts.sh
# so it applies to all commands, not just for the config-*/cdktf-* commands.
AFON_CONFIG_SWITCHED=
if [ -n "$AFON_CONFIG_ENV" ]; then
  "$AFON_CLI_DIR/afon-cli-scripts.sh" --home "$AFON_HOME" --config-env "$AFON_CONFIG_ENV" config-switch
  AFON_CONFIG_SWITCHED=true
fi
AFON_CDKTF_SWITCHED=
if [ -n "$AFON_CDKTF_ENV" ]; then
  "$AFON_CLI_DIR/afon-cli-scripts.sh" --home "$AFON_HOME" --cdktf "$AFON_CDKTF" --cdktf-env "$AFON_CDKTF_ENV" cdktf-switch
  AFON_CDKTF_SWITCHED=true
fi

# AFON_CONFIG_ENV/AFON_CDKTF_ENV have no -D system property counterpart; export them so the
# script commands (which exec afon-cli-scripts.sh) inherit them from this process's env.
# AFON_CONFIG_SWITCHED/AFON_CDKTF_SWITCHED are internal: they tell afon-cli-scripts.sh the
# switch above already ran, so it doesn't redundantly repeat it for a script command.
export AFON_CONFIG_ENV AFON_CDKTF_ENV AFON_CONFIG_SWITCHED AFON_CDKTF_SWITCHED

AFON_JVM_ENVS=
for pair in $AFON_ENVS; do
  AFON_JVM_ENVS="$AFON_JVM_ENVS -D$pair"
done
AFON_JVM_ENVS="${AFON_JVM_ENVS# }"
AFON_JVM_FLAGS="--add-exports=java.base/sun.util.calendar=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.net.util=ALL-UNNAMED --add-exports=java.management/sun.management=ALL-UNNAMED --add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED --add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --enable-preview"

exec java $AFON_JVM_FLAGS \
          $AFON_JVM_ENVS \
    -Dafon.home="$AFON_HOME" \
    -Dafon.cdktf="$AFON_CDKTF" \
    -Dafon.cloud="$AFON_CLOUD" \
    -Dafon.logging="$AFON_LOGGING" \
    -Dafon.logging.console="$AFON_LOGGING_CONSOLE" \
    -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
    -Dslf4j.internal.verbosity=WARN \
    -jar "$JAR" "$@"
