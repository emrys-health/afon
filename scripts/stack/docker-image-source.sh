# Sourced by the scripts that need the reference of a Docker image of docker-images.yaml, whichever way they are run, asking the
# ops command docker-image of the launcher, as scripts/stack/needs-source.sh does for stack-needs: the one in AFON_ROOT, ie
# afon-cli.sh of the cli distribution or afon.sh of the source tree, else AFON_CLI_DIR/afon-cli.sh.
#
# docker_image <name>
#   Prints the reference of the image, eg postgres:16-alpine for postgres-alpine.  Returns 1, saying why, if it cannot.
docker_image() {
  local name="$1" output line
  if [ -x "${AFON_ROOT:-}/afon-cli.sh" ]; then
    output=$("$AFON_ROOT/afon-cli.sh" docker-image "$name" 2>&1) || { echo "$output" >&2; return 1; }
  elif [ -x "${AFON_ROOT:-}/afon.sh" ]; then
    output=$("$AFON_ROOT/afon.sh" --quiet ${AFON_HOME:+--home "$AFON_HOME"} -a docker-image -- "$name" 2>&1) \
      || { echo "$output" >&2; return 1; }
  elif [ -x "${AFON_CLI_DIR:-}/afon-cli.sh" ]; then
    output=$("$AFON_CLI_DIR/afon-cli.sh" docker-image "$name" 2>&1) || { echo "$output" >&2; return 1; }
  else
    echo "-- Cannot find out the image '$name': run this script with afon-cli.sh or afon.sh" >&2
    return 1
  fi
  while IFS= read -r line; do
    case "$line" in
      AFON_DOCKER_IMAGE=*) echo "${line#*=}"; return 0 ;;
    esac
  done <<< "$output"
  echo "-- Cannot find out the image '$name': ${output:-no answer}" >&2
  return 1
}
