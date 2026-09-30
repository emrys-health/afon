#!/usr/bin/env bash
#
# Updates Jupyter notebooks for the Spark Connect server that registers no catalogs of its own (#86): each
# session must register the Polaris catalogs it uses, authenticated with the user's OIDC token, which
# JupyterHub injects into the pod as the OIDC_TOKEN environment variable.
#
# usage: demo/update-notebooks-oidc-token.sh [--catalogs <c1,c2,...>] [--dry-run] [notebook...]
#   --catalogs  the Polaris catalogs to register in sessions that register none (default: aws,azure,gcp)
#   --dry-run   report what would change, without writing
#   notebook    the notebooks to update (default: demo/notebooks/*.ipynb)
#
# In each notebook it:
#   - replaces a session created with no catalogs, ie a line like
#       spark = SparkSession.builder.appName("test").remote("sc://...").getOrCreate()
#     with one registering --catalogs, each authenticated with OIDC_TOKEN;
#   - replaces a hard-coded token, ie OIDC_TOKEN = """eyJ...""", with OIDC_TOKEN = os.environ["OIDC_TOKEN"].
# Sessions that already register catalogs are left alone, as is everything else, including cell outputs.
# Re-running it changes nothing more.  Review the result: a notebook may need other catalogs than --catalogs
# (eg aws_forbidden), or rewriting.
#
# The Polaris and Keycloak hosts are read from POLARIS_DOMAIN_NAME and OIDC_DOMAIN_NAME when the notebook
# runs, defaulting to emryspolaris.click and emrysoauth.click.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOGS="aws,azure,gcp"
DRY_RUN=false
NOTEBOOKS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --catalogs|-c) CATALOGS="$2"; shift 2 ;;
    --dry-run|-n) DRY_RUN=true; shift ;;
    --help|-h) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *) NOTEBOOKS+=("$1"); shift ;;
  esac
done
if [ ${#NOTEBOOKS[@]} -eq 0 ]; then
  NOTEBOOKS=("$DEMO_DIR"/notebooks/*.ipynb)
fi

python3 - "$CATALOGS" "$DRY_RUN" "${NOTEBOOKS[@]}" <<'PYTHON'
import json
import re
import sys

catalogs = [c.strip() for c in sys.argv[1].split(",") if c.strip()]
dry_run = sys.argv[2] == "true"
notebooks = sys.argv[3:]

SESSION = re.compile(
    r'^(?P<indent>[ \t]*)spark\s*=\s*SparkSession\.builder\.appName\((?P<app>"[^"]*"|\'[^\']*\')\)'
    r'\.remote\((?P<remote>"[^"]*"|\'[^\']*\')\)\.getOrCreate\(\)[ \t]*$', re.M)
HARD_CODED_TOKEN = re.compile(r'OIDC_TOKEN\s*=\s*(?:"""|\'\'\'|"|\')eyJ[^"\']*(?:"""|\'\'\'|"|\')')
COMMENTED_ENV_TOKEN = re.compile(r'^[ \t]*#\s*OIDC_TOKEN\s*=\s*os\.environ\["OIDC_TOKEN"\][ \t]*\n', re.M)


def session_code(indent, app, remote):
    names = ", ".join(f'"{c}"' for c in catalogs)
    code = f'''# The Spark Connect server has no catalogs of its own: each session registers the Polaris catalogs
# it uses, authenticated with the user's OIDC token, which JupyterHub injects into the pod.
OIDC_TOKEN = os.environ["OIDC_TOKEN"]
POLARIS_DOMAIN_NAME = os.environ.get("POLARIS_DOMAIN_NAME", "emryspolaris.click")
OIDC_DOMAIN_NAME = os.environ.get("OIDC_DOMAIN_NAME", "emrysoauth.click")


def with_catalog(builder, name, warehouse=None):
    """Registers the Polaris catalog warehouse (default: name) as the Spark catalog name."""
    prefix = f"spark.sql.catalog.{{name}}"
    return (builder
            .config(prefix, "org.apache.iceberg.spark.SparkCatalog")
            .config(f"{{prefix}}.type", "rest")
            .config(f"{{prefix}}.uri", f"https://{{POLARIS_DOMAIN_NAME}}/api/catalog")
            .config(f"{{prefix}}.warehouse", warehouse or name)
            .config(f"{{prefix}}.cache-enabled", "false")
            .config(f"{{prefix}}.header.X-Iceberg-Access-Delegation", "vended-credentials")
            .config(f"{{prefix}}.oauth2-server-uri", f"https://{{OIDC_DOMAIN_NAME}}/realms/polaris/protocol/openid-connect/token")
            .config(f"{{prefix}}.scope", "openid")
            .config(f"{{prefix}}.token", OIDC_TOKEN))


builder = SparkSession.builder.appName({app}).remote({remote})
for catalog in [{names}]:
    builder = with_catalog(builder, catalog)
spark = builder.getOrCreate()'''
    return "\n".join(indent + line if line else line for line in code.split("\n"))


def with_os_import(source):
    if re.search(r'^\s*import os\b', source, re.M):
        return source
    return "import os\n" + source


def update(source):
    changes = []
    updated = SESSION.sub(lambda m: session_code(m["indent"], m["app"], m["remote"]), source)
    if updated != source:
        changes.append(f"registers catalogs {', '.join(catalogs)}")
    token_updated = HARD_CODED_TOKEN.sub('OIDC_TOKEN = os.environ["OIDC_TOKEN"]', updated)
    if token_updated != updated:
        token_updated = COMMENTED_ENV_TOKEN.sub("", token_updated)
        changes.append("reads OIDC_TOKEN from the environment instead of a hard-coded token")
    if changes:
        token_updated = with_os_import(token_updated)
    return token_updated, changes


updated_count = 0
for path in notebooks:
    with open(path) as f:
        notebook = json.load(f)
    notebook_changes = []
    for index, cell in enumerate(notebook.get("cells", [])):
        if cell.get("cell_type") != "code":
            continue
        source = "".join(cell["source"])
        updated, changes = update(source)
        if changes:
            cell["source"] = updated.splitlines(True)
            notebook_changes += [f"cell {index}: {change}" for change in changes]
    if notebook_changes:
        updated_count += 1
        print(f"{'Would update' if dry_run else 'Updated'} {path}")
        for change in notebook_changes:
            print(f"  {change}")
        if not dry_run:
            with open(path, "w") as f:
                json.dump(notebook, f, indent=1, ensure_ascii=False)
                f.write("\n")
    else:
        print(f"Unchanged {path}")

print(f"{updated_count} of {len(notebooks)} notebooks {'would be ' if dry_run else ''}updated")
PYTHON
