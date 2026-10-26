#!/usr/bin/env bash
set -euo pipefail

# Generates synthetic FHIR NSCLC EHR data for one site/env, then imports the
# resulting parquet files into that env via SparkTestImporter.
# See README.md for the underlying python usage.
#
# Runs in the common python env (scripts/python).  The afon commands are run with './afon.sh -a <command>', or, from a
# cli distribution, which has no afon.sh, with its afon-cli.sh, eg as 'afon-cli.sh data-synthetic-ehr --site <site>'.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_ROOT="${AFON_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

# Runs the afon command $1 with the args $2...: with afon.sh, if the script runs from the source tree, else with the
# afon-cli.sh of the cli distribution it runs from, or that in AFON_CLI_DIR.
afon() {
  local command="$1"
  shift
  if [ -x "$AFON_ROOT/afon.sh" ]; then
    "$AFON_ROOT/afon.sh" -a "$command" -- "$@"
  else
    SPRING_MAIN_BANNER_MODE="${SPRING_MAIN_BANNER_MODE:-off}" "${AFON_CLI_DIR:-$AFON_ROOT}/afon-cli.sh" "$command" "$@"
  fi
}

SITES=(emrys westmids wessex tvs)

usage() {
  echo "Usage: $0 --site <site> [--cloud <cloud>] [--patients <n>] [--seed <n>] [--output <dir>] [--help|-h]"
  echo "  --site      Site/env to generate for, one of: ${SITES[*]} (required)"
  echo "  --cloud     Cloud to deploy to (default azure)"
  echo "  --patients  Number of patients to generate (default 800)"
  echo "  --seed      Random seed for reproducibility (default (RANDOM % 100) + 1)"
  echo "  --output    Output root directory (default $AFON_ROOT/target/synthetic-ehr); parquet files are written to <output>/<site>"
}

patients=800
seed="$(( (RANDOM % 100) + 1 ))"
output="$AFON_ROOT/target/synthetic-ehr"
site=
cloud="azure"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --site) site="$2"; shift 2 ;;
    --cloud) cloud="$2"; shift 2 ;;
    --patients) patients="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$site" ]]; then
  echo "Missing required --site argument." >&2
  usage
  exit 1
fi
if [[ ! " ${SITES[*]} " == *" $site "* ]]; then
  echo "Invalid --site '$site'. Choices: ${SITES[*]}" >&2
  exit 1
fi

# the afon env is the site, as for './afon.sh --env <site>': the config read, and used by every afon command, is
# $AFON_HOME/config-<site>
export AFON_ENV="$site"

source "$AFON_ROOT/scripts/python/python-source.sh"

site_output="$output/$site"
echo "Generating $patients synthetic patients for site '$site' (seed $seed)..."
python "$SCRIPT_DIR/generate_synthetic_ehr.py" \
  --patients "$patients" \
  --site "$site" \
  --seed "$seed" \
  --output "$site_output"

site_dir="$(cd "$site_output" && pwd)"
echo "Importing parquet files from $site_dir into cloud/catalog '$cloud' env '$site' via SparkTestImporter..."
afon spark-test-importer "$cloud" "$cloud" synthetic_ehr any "$site_dir"
