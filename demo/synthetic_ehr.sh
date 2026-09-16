#!/usr/bin/env bash
set -euo pipefail

# Generates synthetic FHIR NSCLC EHR data for one site/env, then imports the
# resulting parquet files into that env via SparkTestImporter.
# See README.md for the underlying python usage.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_SH="$SCRIPT_DIR/../afon.sh"
cd "$SCRIPT_DIR"

SITES=(emrys westmids wessex tvs)

usage() {
  echo "Usage: $0 --site <site> [--cloud <cloud>] [--patients <n>] [--seed <n>] [--output <dir>] [--help|-h]"
  echo "  --site      Site/env to generate for, one of: ${SITES[*]} (required)"
  echo "  --cloud     Cloud to deploy to (default azure)"
  echo "  --patients  Number of patients to generate (default 800)"
  echo "  --seed      Random seed for reproducibility (default (RANDOM % 100) + 1)"
  echo "  --output    Output root directory (default target); parquet files are written to <output>/<site>"
}

patients=800
seed="$(( (RANDOM % 100) + 1 ))"
output="target"
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

if [[ ! -d .venv ]]; then
  uv venv
fi
source .venv/bin/activate
uv sync

site_output="$output/$site"
echo "Generating $patients synthetic patients for site '$site' (seed $seed)..."
python generate_synthetic_ehr.py \
  --patients "$patients" \
  --site "$site" \
  --seed "$seed" \
  --output "$site_output"

echo "Switching config to env $site"
"$AFON_SH" -a config-switch -e $site --mvn-quiet
site_dir="$(cd "$site_output" && pwd)"
echo "Importing parquet files from $site_dir into cloud/catalog '$cloud' env '$site' via SparkTestImporter..."
"$AFON_SH" -a exec -ec ops.spark.SparkTestImporter -ea "$cloud $cloud synthetic_ehr any $site_dir" --mvn-quiet
