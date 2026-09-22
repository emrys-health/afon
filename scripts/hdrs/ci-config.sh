#!/usr/bin/env bash
#
# Move the ~/.afon/config file set between this machine and AWS Secrets
# Manager, so that .github/workflows/tofu.yml can synthesise afon in CI.
#
# WHY THIS EXISTS. afon cannot synthesise without its config - the property
# files carry every hostname, subnet id, bucket and certificate the stack is
# built from - so without a copy CI can reach, there is no cdk.tf.json and
# therefore no plan. Upstream solves the same problem with
# `scripts/github/secrets.sh push`, which uploads each file into a GitHub
# Actions secret. HDRS.md rules that out: this config carries the RDS master
# password and the svc user's access key, and GitHub is not where those belong.
# Secrets Manager is - it is already the organisation's chosen secrets backend
# (docs-decisions, open decision 3) and it keeps the config on the same side of
# the trust boundary as the things it describes.
#
# WHAT IS AND IS NOT IN THE BUNDLE. The two database passwords are pushed like
# everything else, but the pipeline OVERWRITES them at run time from the live
# RDS master secret. That is deliberate: AWS rotates that credential, static
# copies go stale silently, and the last time they did it cost a three-day
# Polaris outage that nobody diagnosed (HDRS.md). So this bundle going stale on
# those two keys is harmless, and going stale on any other key is not - re-push
# whenever the config changes.
#
# THE BUNDLE IS NOT A BACKUP. It is an input to CI. The operator's
# ~/.afon/config remains the source of truth, and `pull` exists to seed a new
# machine or to check for drift, not to be the primary copy.
#
#   ./scripts/hdrs/ci-config.sh push          # laptop -> Secrets Manager
#   ./scripts/hdrs/ci-config.sh diff          # what would change
#   ./scripts/hdrs/ci-config.sh pull <dir>    # Secrets Manager -> a directory
#
# Values are never printed. `diff` reports file names and whether their
# contents match, which is all you need and nothing you would not want in a
# terminal history.

set -euo pipefail

SECRET_ID="${AFON_CI_CONFIG_SECRET:-hdrs-poc/afon-core-ci-config}"
AWS_PROFILE_ARG="${AWS_PROFILE:+--profile $AWS_PROFILE}"
AWS_REGION_NAME="${AWS_REGION:-eu-west-2}"
CONFIG_DIR="${AFON_HOME:-$HOME/.afon}/config"

# Only these. A glob would sweep up whatever else is in the directory -
# secret.properties.bak-20260828T145032Z is sitting there right now, and a
# stale password uploaded under a name CI does not read is the sort of thing
# that is found much later, by someone else.
FILES=(
  afon.properties
  afon-aws.properties
  afon-azure.properties
  afon-gcp.properties
  secret.properties
  secret-aws.properties
  secret-azure.properties
  secret-gcp.properties
  users.csv
  users-acl.csv
)

usage() {
  echo "Usage: $0 <push|pull|diff> [target-dir]"
  echo
  echo "  push          upload $CONFIG_DIR to $SECRET_ID"
  echo "  pull <dir>    write the bundle into <dir> (must not already exist)"
  echo "  diff          compare $CONFIG_DIR with the bundle, by name and digest"
  echo
  echo "Environment: AFON_CI_CONFIG_SECRET, AWS_PROFILE, AWS_REGION, AFON_HOME"
  exit 2
}

aws_cli() {
  # shellcheck disable=SC2086 # deliberate word splitting on the profile flag
  aws $AWS_PROFILE_ARG --region "$AWS_REGION_NAME" "$@"
}

require_config_dir() {
  if [ ! -d "$CONFIG_DIR" ]; then
    echo "No config directory at $CONFIG_DIR" >&2
    exit 1
  fi
}

# Build the JSON object CI reads: filename -> contents, verbatim. jq's
# --rawfile keeps multi-line values (SNOWFLAKE_PRIVATE_KEY) byte for byte,
# which is the whole reason upstream's version base64s everything.
build_bundle() {
  local args=() filter="{}" name
  for name in "${FILES[@]}"; do
    if [ ! -f "$CONFIG_DIR/$name" ]; then
      echo "  skipping $name (not present)" >&2
      continue
    fi
    args+=(--rawfile "f_${name//[.-]/_}" "$CONFIG_DIR/$name")
    filter="$filter + {\"$name\": \$f_${name//[.-]/_}}"
  done
  if [ "$filter" = "{}" ]; then
    echo "Nothing to push - no config files found in $CONFIG_DIR" >&2
    exit 1
  fi
  jq -n "${args[@]}" "$filter"
}

fetch_bundle() {
  if ! aws_cli secretsmanager get-secret-value --secret-id "$SECRET_ID" \
        --query SecretString --output text 2>/dev/null; then
    echo "Cannot read $SECRET_ID - is it created (hdrsuk/infra-aws-poc, github-afon-core.tf) and populated?" >&2
    exit 1
  fi
}

cmd_push() {
  require_config_dir
  echo "Building the bundle from $CONFIG_DIR"
  local bundle
  bundle=$(build_bundle)

  # Through a file with a tight umask, never an argument: an argument is
  # visible in the process table to every other user on the machine.
  #
  # NOT `local`, and the trap is set after the assignment. An EXIT trap runs
  # after the function's scope is gone, so a `local tmp` is unset by the time
  # it fires - under `set -u` the trap then dies on an unbound variable and the
  # file survives, holding the entire config bundle in clear. Observed on the
  # first real run; the file was shredded by hand. `${TMP_BUNDLE:-}` keeps the
  # trap harmless if it somehow fires before the assignment.
  TMP_BUNDLE=$(umask 077 && mktemp)
  trap 'rm -f "${TMP_BUNDLE:-}"' EXIT
  printf '%s' "$bundle" > "$TMP_BUNDLE"

  aws_cli secretsmanager put-secret-value --secret-id "$SECRET_ID" \
    --secret-string "file://$TMP_BUNDLE" --output text --query VersionId

  # Explicitly, as well as on the trap: the trap is the safety net for a
  # failure path, not the normal one.
  rm -f "$TMP_BUNDLE"
  echo "Pushed $(printf '%s' "$bundle" | jq 'keys|length') file(s) to $SECRET_ID"
  echo
  echo "Reminder: POLARIS_DB_PASSWORD and KC_DB_PASSWORD in this bundle are ignored"
  echo "by CI, which reads the live RDS master secret instead. Every other key matters."
}

cmd_pull() {
  local dest="${1:-}"
  [ -n "$dest" ] || usage
  if [ -e "$dest" ]; then
    echo "$dest already exists - refusing to overwrite a config directory." >&2
    exit 1
  fi
  umask 077
  mkdir -p "$dest"
  local bundle
  bundle=$(fetch_bundle)
  local name
  while IFS= read -r name; do
    printf '%s' "$bundle" | jq -j --arg k "$name" '.[$k]' > "$dest/$name"
    chmod 600 "$dest/$name"
    echo "  wrote $name ($(wc -c < "$dest/$name") bytes)"
  done < <(printf '%s' "$bundle" | jq -r 'keys[]')
  echo "Pulled into $dest"
}

cmd_diff() {
  require_config_dir
  local bundle
  bundle=$(fetch_bundle)
  local drift=0 name local_sum remote_sum
  for name in "${FILES[@]}"; do
    local has_local=no has_remote=no
    [ -f "$CONFIG_DIR/$name" ] && has_local=yes
    printf '%s' "$bundle" | jq -e --arg k "$name" 'has($k)' >/dev/null && has_remote=yes

    if [ "$has_local" = no ] && [ "$has_remote" = no ]; then
      continue
    elif [ "$has_local" = no ]; then
      echo "  only in the bundle : $name"; drift=1; continue
    elif [ "$has_remote" = no ]; then
      echo "  only local         : $name"; drift=1; continue
    fi

    local_sum=$(sha256sum < "$CONFIG_DIR/$name" | cut -c1-12)
    remote_sum=$(printf '%s' "$bundle" | jq -j --arg k "$name" '.[$k]' | sha256sum | cut -c1-12)
    if [ "$local_sum" = "$remote_sum" ]; then
      echo "  same               : $name"
    else
      echo "  DIFFERS            : $name (local $local_sum, bundle $remote_sum)"
      drift=1
    fi
  done
  if [ "$drift" -eq 0 ]; then
    echo "The bundle matches $CONFIG_DIR."
  else
    echo
    echo "Run '$0 push' to make the bundle match this machine."
    echo "Note that POLARIS_DB_PASSWORD and KC_DB_PASSWORD may differ harmlessly:"
    echo "CI overwrites both from the live RDS master secret on every run."
  fi
}

case "${1:-}" in
  push) cmd_push ;;
  pull) shift; cmd_pull "${1:-}" ;;
  diff) cmd_diff ;;
  *)    usage ;;
esac
