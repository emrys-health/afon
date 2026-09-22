#!/bin/sh
# Report how far Emrys upstream has moved since our snapshot, and - more
# importantly - whether it has touched any file we patched.
#
# Our `main` is a single squashed import, so a normal `git log main..emrys/main`
# is meaningless. The commit we imported (56cfb474) is pinned by SHA below;
# everything is measured against that.
#
# Run monthly, or before adopting anything from upstream.
set -e
cd "$(dirname "$0")/../.." || exit 1

git remote get-url emrys >/dev/null 2>&1 || {
  echo "No 'emrys' remote. Add it:"
  echo "  git remote add emrys https://github.com/emrys-health/afon-core.git"; exit 1; }

echo "Fetching upstream..."
git fetch -q emrys

# The import point is pinned by SHA, not by a branch in this repo. `main` is a
# squashed snapshot, so that commit exists only in Emrys's history: a branch at
# it would have to push ~1,030 upstream commits into hdrsuk/afon-core, which
# secret-scanning push protection blocks (that history carries credentials).
# The SHA resolves in any clone once the fetch above has run. A local `upstream`
# branch, if you still have one, is accepted as a fallback.
IMPORT_POINT=56cfb474148cd14564af9b840a62e2e2902ccb57
BASE=$(git rev-parse --verify -q "$IMPORT_POINT^{commit}") \
  || BASE=$(git rev-parse --verify -q upstream) \
  || { echo "Import point $IMPORT_POINT not found - did the emrys fetch succeed?"; exit 1; }
AHEAD=$(git log --oneline "$BASE"..emrys/main 2>/dev/null | wc -l | tr -d ' ')

echo
echo "==============================================================="
echo " Our import point : $(git log --oneline -1 "$BASE")"
echo " Upstream main    : $(git log --oneline -1 emrys/main)"
echo " Upstream is $AHEAD commits ahead"
echo "==============================================================="

# Files carrying HDRS patches. If upstream touches one, our patch may
# conflict - or may have been fixed upstream and become removable.
# Refreshed for the 56cfb474 import. Three changes worth noting:
#  - AfonProperties (class of static fields) became the AfonProperty enum ([#130]).
#  - PolarisConfigClient became PolarisCatalogClient ([#154]).
#  - JupyterHubConfig and NetworkConfig are NEW to this list. The old list omitted
#    JupyterHubConfig, which carried more HDRS code than any other file - the audit
#    perimeter, the spawn fixes - so upstream could move under it unnoticed.
#  - StepOperations and afon-ops/pom.xml are GONE from the list: upstream fixed the
#    problems Patches 9 and 10 worked around, so we no longer patch either.
PATCHED="afon-cdk/src/main/java/health/emrys/afon/cdk/aws/AwsCloudProvider.java
afon-cdk/src/main/java/health/emrys/afon/cdk/aws/AwsPolarisStack.java
afon-cdk/src/main/java/health/emrys/afon/cdk/aws/AwsSparkStack.java
afon-cdk/src/main/java/health/emrys/afon/cdk/AbstractCloudStack.java
afon-cdk/src/main/java/health/emrys/afon/cdk/JupyterHubConfig.java
afon-cdk/src/main/java/health/emrys/afon/cdk/NetworkConfig.java
afon-cdk/src/main/java/health/emrys/afon/cdk/PolarisConfig.java
afon-polaris/src/main/java/health/emrys/afon/polaris/config/PolarisCatalogClient.java
afon-config/src/main/java/health/emrys/afon/config/AfonProperty.java
templates/config/afon-aws.properties
scripts/aws/user/trust-policy.json
scripts/aws/user/glue-iceberg-assume-role.json"

echo
echo "Upstream changes to files we patched:"
# Collected in a command substitution rather than a `... | while` pipeline: the
# pipeline ran the loop in a subshell, so the "any hits?" flag never survived it
# and the script printed "(none)" underneath a list of hits.
HITS=$(for f in $PATCHED; do
  N=$(git log --oneline "$BASE"..emrys/main -- "$f" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$N" != "0" ]; then
    printf "  %-70s %s commits\n" "$(basename "$f")" "$N"
    git log --oneline "$BASE"..emrys/main -- "$f" | head -3 | sed 's/^/      /'
  fi
done)
if [ -n "$HITS" ]; then echo "$HITS"; else echo "  (none)"; fi

echo
echo "Interesting upstream work (may supersede our patches):"
git log --oneline "$BASE"..emrys/main 2>/dev/null \
  | grep -iE "cloud-aware|single.cloud|aws.only|bootstrap|realm|keycloak|principal|quote|secret" \
  | head -8 | sed 's/^/  /' || echo "  (nothing matching)"

echo
echo "Our patches, for comparison:"
git log --oneline main 2>/dev/null | grep -c "^[0-9a-f]* Patch " | xargs echo "  HDRS patch commits:"
echo
echo "To review a specific upstream change:  git show <sha>"
echo "Our diff vs the import point:          git diff $BASE..main"
