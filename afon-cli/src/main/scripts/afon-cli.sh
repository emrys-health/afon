#!/usr/bin/env bash
# Runs the afon-cli executable jar with the JVM flags afon requires
# (see the afon.jvm/afon.exec properties in the root pom.xml).
# Looks for the jar in the dir specified by the env $AFON_CLI_DIR,
# falling back on the script dir.
set -euo pipefail

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

AFON_HOME="${AFON_HOME:-$HOME/.afon}"
AFON_CDKTF="${AFON_CDKTF:-$AFON_HOME/cdktf}"
AFON_CLOUD="${AFON_CLOUD:-ALL}"
AFON_LOGGING="${AFON_LOGGING:-INFO}"
AFON_LOGGING_CONSOLE="${AFON_LOGGING_CONSOLE:-OFF}"

AFON_JVM_FLAGS="--add-exports=java.base/sun.util.calendar=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.net.util=ALL-UNNAMED --add-exports=java.management/sun.management=ALL-UNNAMED --add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED --add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED --add-modules=jdk.incubator.vector --add-opens=java.base/java.nio=ALL-UNNAMED --enable-preview"

exec java $AFON_JVM_FLAGS \
    -Dafon.home="$AFON_HOME" \
    -Dafon.cdktf="$AFON_CDKTF" \
    -Dafon.cloud="$AFON_CLOUD" \
    -Dafon.logging="$AFON_LOGGING" \
    -Dafon.logging.console="$AFON_LOGGING_CONSOLE" \
    -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
    -jar "$JAR" "$@"
