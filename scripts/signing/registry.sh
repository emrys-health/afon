#!/usr/bin/env bash
# Shows the registry that the images of Kyverno are pulled from, ie KYVERNO_IMAGE_REGISTRY of $AFON_HOME/config/afon.properties, or that of the
# chart, reg.kyverno.io, when it is empty or missing, and checks that the registry is responding and has the images, so that one can tell, before a
# deployment or a run of the signing acceptance test, whether it is up, and whether to set the property to another, see docs/stacks.md.
#
# Usage:
#   ./registry.sh [<registry>]
#
#   <registry>  A registry to check instead of the one in use, eg ghcr.io, to see if it is up before setting the property to it.
#
# For each image of the Kyverno chart (kyverno, kyvernopre, kyverno-cli and readiness-checker, at the version of the chart, read from the chart
# of this repository when it is there, else the one fixed in the script) it asks the registry, anonymously, for the manifest of the image, as a node does to pull it.  The registry
# may answer its base address and yet fail to serve the images, as reg.kyverno.io does when it is in trouble, so the base address is only the first check.
# Note that the chart pulls readiness-checker from ghcr.io unless the property is set: this is reported as such.
#
# Exit code: 0 if the registry responds and has every image, 1 if not.
#
# Examples:
#   ./registry.sh
#   ./registry.sh ghcr.io

# No -e, even when run by afon-cli-scripts.sh, which has it: a registry that does not answer, or a chart that is not there, is reported, not fatal
set -uo pipefail
set +e

DEFAULT_REGISTRY="reg.kyverno.io"
# the registry of readiness-checker in the chart, which the property overrides like the rest
CHART_READINESS_REGISTRY="ghcr.io"
TIMEOUT=15
# The version of the images when the chart is not at hand, ie in the cli distribution, which has no chart: that of the chart vendored in
# afon-cdk, kyverno-<chart version>.tgz, whose appVersion it is.  Keep it in step when the chart is upgraded.
FALLBACK_TAG="v1.19.2"

usage() {
  sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

REQUESTED=""
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --*) echo "Unknown option $1" >&2; usage >&2; exit 1 ;;
  "") ;;
  *) REQUESTED="$1" ;;
esac
if [ $# -gt 1 ]; then
  usage >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "- curl is not installed" >&2
  exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
PROPS="$AFON_HOME/config/afon.properties"

# The registry in use, as the config says: read as text, as the other scripts of signing do
CONFIGURED=""
if [ -f "$PROPS" ]; then
  CONFIGURED="$(sed -n 's/^KYVERNO_IMAGE_REGISTRY=//p' "$PROPS" | tail -1 | tr -d '[:space:]')"
fi
if [ -n "$CONFIGURED" ]; then
  IN_USE="$CONFIGURED"
  SOURCE="KYVERNO_IMAGE_REGISTRY in $PROPS"
else
  IN_USE="$DEFAULT_REGISTRY"
  SOURCE="the registry of the chart: KYVERNO_IMAGE_REGISTRY is empty or not set in $PROPS"
fi

# The version of the images is the appVersion of the vendored chart, if this is run from the repository
TAG="$FALLBACK_TAG"
CHART_NOTE="fixed in the script, as the chart is not here"
CHART="$(ls "$HERE"/../../afon-cdk/src/main/resources/charts/kyverno-*.tgz 2>/dev/null | tail -1)"
if [ -n "$CHART" ]; then
  FROM_CHART="$(tar -xzOf "$CHART" kyverno/Chart.yaml 2>/dev/null | sed -n 's/^appVersion: *//p' | tr -d '"[:space:]')"
  if [ -n "$FROM_CHART" ]; then
    TAG="$FROM_CHART"
    CHART_NOTE="chart $(basename "$CHART")"
  fi
fi

if [ -n "$REQUESTED" ]; then
  CHECKED="$REQUESTED"
  echo "Kyverno images in use: $IN_USE ($SOURCE)"
  echo "Checking instead:      $CHECKED"
else
  CHECKED="$IN_USE"
  echo "Kyverno images from: $IN_USE ($SOURCE)"
fi
echo "Version of the images: $TAG ($CHART_NOTE)"
echo

# The HTTP status of a request to the registry, 000 if it does not answer
status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT" "$@" 2>/dev/null || true
}

# The status of the manifest of repository:tag in the registry, with the anonymous token that the registry asks for, if it does: the answer to the
# first request names where to get it, as Docker does
manifest_status() {
  local REGISTRY="$1" REPOSITORY="$2" TAG="$3" URL CODE CHALLENGE REALM SERVICE TOKEN
  URL="https://$REGISTRY/v2/$REPOSITORY/manifests/$TAG"
  local ACCEPT=(-H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
  CODE="$(status -I "${ACCEPT[@]}" "$URL")"
  if [ "$CODE" == "401" ]; then
    CHALLENGE="$(curl -s -I --max-time "$TIMEOUT" "${ACCEPT[@]}" "$URL" 2>/dev/null | tr -d '\r' | grep -i '^www-authenticate:' | head -1)"
    REALM="$(printf '%s' "$CHALLENGE" | sed -n 's/.*realm="\([^"]*\)".*/\1/p')"
    SERVICE="$(printf '%s' "$CHALLENGE" | sed -n 's/.*service="\([^"]*\)".*/\1/p')"
    if [ -n "$REALM" ]; then
      TOKEN="$(curl -s --max-time "$TIMEOUT" "$REALM?service=$SERVICE&scope=repository:$REPOSITORY:pull" 2>/dev/null \
               | sed -n 's/.*"\(access_\)\{0,1\}token" *: *"\([^"]*\)".*/\2/p' | head -1)"
      [ -n "$TOKEN" ] && CODE="$(status -I "${ACCEPT[@]}" -H "Authorization: Bearer $TOKEN" "$URL")"
    fi
  fi
  printf '%s' "$CODE"
}

FAILED=0
BASE="$(status "https://$CHECKED/v2/")"
case "$BASE" in
  200|401) echo "OK     https://$CHECKED/v2/ answers ($BASE)" ;;
  *) echo "FAILED https://$CHECKED/v2/ answers $BASE (000: no answer within ${TIMEOUT}s)"; FAILED=1 ;;
esac

if [ "$FAILED" == 0 ]; then
  for IMAGE in kyverno kyvernopre kyverno-cli readiness-checker; do
    REGISTRY="$CHECKED"
    # with no property set the chart pulls readiness-checker from ghcr.io, whatever the registry that is being looked at
    if [ "$IMAGE" == "readiness-checker" ] && [ -z "$CONFIGURED" ] && [ -z "$REQUESTED" ]; then
      REGISTRY="$CHART_READINESS_REGISTRY"
    fi
    CODE="$(manifest_status "$REGISTRY" "kyverno/$IMAGE" "$TAG")"
    if [ "$CODE" == "200" ]; then
      echo "OK     $REGISTRY/kyverno/$IMAGE:$TAG"
    else
      echo "FAILED $REGISTRY/kyverno/$IMAGE:$TAG answers $CODE"
      FAILED=1
    fi
  done
fi

echo
if [ "$FAILED" == 0 ]; then
  echo "$CHECKED is responding"
else
  echo "$CHECKED is NOT responding properly: set KYVERNO_IMAGE_REGISTRY in $PROPS to a registry that is (check one with: afon-cli.sh signing-registry ghcr.io) and deploy again, see docs/stacks.md"
  exit 1
fi
