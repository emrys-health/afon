# The versions of the local tools that afon setups are known to work with, to make local setups reproducible.
# Sourced by setup.sh, which installs these versions from release downloads, except git, java, maven and the cloud clis, which
# come from apt or brew, and by versions.sh, which checks the installed versions against them.
# Pin to a prefix to allow any version under it, eg java 17 allows 17.0.20.1.
# PINNED_<tool> applies to all platforms; PINNED_<tool>_linux or PINNED_<tool>_macos overrides it for that platform,
# as apt and brew offer different versions of what they provide.  On Ubuntu, PINNED_<tool>_ubuntu<release without the dot>, eg
# PINNED_git_ubuntu2204 for 22.04, overrides the linux one, as each release offers other versions.

PINNED_git=2.56
PINNED_java=17.0.20
PINNED_gh=2.103.0
PINNED_mvn=3.10.0
PINNED_docker=29.9.1
PINNED_tofu=1.13.1
PINNED_kubectl=1.37.1
PINNED_jq=1.8.1
PINNED_yq=4.54.1
PINNED_helm=4.1.1
PINNED_hubble=1.19.4
PINNED_kind=0.33.0
PINNED_cosign=3.1.4
PINNED_aws=2.38.3
PINNED_eksctl=0.231.0
PINNED_az=2.90.0
PINNED_gcloud=587.0.0
PINNED_kubelogin=0.2.20

# Linux (Ubuntu 26.04 resolute) overrides, where apt does not offer the common pin.
PINNED_git_linux=2.53
PINNED_mvn_linux=3.9.12
PINNED_aws_linux=2.31.35

# Ubuntu 22.04 (jammy) overrides.
PINNED_git_ubuntu2204=2.34
PINNED_mvn_ubuntu2204=3.6.3

# macOS overrides, where brew does not offer the common pin.  Brew offers only the current version of a formula, and gcloud,
# a cask which updates itself, cannot be pinned.
PINNED_gcloud_macos=590.0.0
PINNED_java_macos=17.0.21

# The version pinned for the tool on this platform, or on the platform in PINNED_PLATFORM (linux or macos) if set,
# eg by the ansible playbooks, which run on another platform than they provision.  On Ubuntu, and not with PINNED_PLATFORM,
# the pin of its release, eg ubuntu2204, comes first.
pinned_version(){
  local platform="${PINNED_PLATFORM:-}" var release=
  if [ -z "$platform" ]; then
    case "$(uname -s)" in
      Linux) platform=linux
             release=$( . /etc/os-release 2>/dev/null && [ "${ID:-}" == ubuntu ] && echo "ubuntu${VERSION_ID//./}" ) || release=
             ;;
      Darwin) platform=macos ;;
    esac
  fi
  if [ -n "$release" ]; then
    var="PINNED_$1_$release"
    if [ -n "${!var:-}" ]; then echo "${!var}"; return; fi
  fi
  var="PINNED_$1_$platform"
  if [ -n "${!var:-}" ]; then echo "${!var}"; return; fi
  var="PINNED_$1"
  echo "${!var:-}"
}

# The java of JAVA_HOME if set, as brew's openjdk is keg-only, ie not on the PATH, otherwise the one on the PATH.
installed_java(){
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then "$JAVA_HOME/bin/java" "$@"; else java "$@"; fi
}

# The installed version of a tool, empty if not installed or not detectable.
installed_version(){
  case "$1" in
    gh) gh --version 2>/dev/null | head -1 | awk '{print $3}' ;;
    git) git --version 2>/dev/null | awk '{print $3}' ;;
    java) installed_java -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/' ;;
    mvn) mvn -B --version 2>/dev/null | head -1 | sed $'s/\033\\[[0-9;]*m//g' | awk '{print $3}' ;;
    docker) docker --version 2>/dev/null | sed -E 's/.*version ([^,]+).*/\1/' ;;
    tofu) tofu --version 2>/dev/null | head -1 | sed 's/OpenTofu v//' ;;
    kubectl) kubectl version --client 2>/dev/null | head -1 | sed 's/.*v//' ;;
    jq) jq --version 2>/dev/null | sed 's/jq-//' ;;
    yq) yq --version 2>/dev/null | sed -E 's/.*version v?//' ;;
    helm) helm version --short 2>/dev/null | sed -E 's/^v//; s/\+.*//' ;;
    kind) kind version 2>/dev/null | awk '{print $2}' | sed -E 's/^v//' ;;
    cosign) cosign version 2>/dev/null | grep -i GitVersion | awk '{print $2}' | sed -E 's/^v//' ;;
    hubble) hubble version 2>/dev/null | head -1 | awk '{print $2}' | sed -E 's/^v//; s/@.*//' ;;
    aws) aws --version 2>/dev/null | sed -E 's#aws-cli/([^ ]+).*#\1#' ;;
    eksctl) eksctl version 2>/dev/null | head -1 | sed -E 's/^v//; s/-.*//' ;;
    az) az version --query '"azure-cli"' -o tsv 2>/dev/null ;;
    gcloud) gcloud --version 2>/dev/null | head -1 | awk '{print $NF}' ;;
    kubelogin) kubelogin --version 2>/dev/null | grep -i 'git hash' | sed -E 's/.*: v?([0-9.]+)\/.*/\1/' ;;
  esac
}

# Prints OK, MISMATCH, MISSING or UNPINNED for the tool, with its installed and pinned versions.
# Returns non-zero for MISMATCH and MISSING.
check_tool_version(){
  local tool=$1 pinned installed
  pinned=$(pinned_version "$tool")
  installed=$(installed_version "$tool")
  if [ -z "$installed" ]; then
    printf '%-10s MISSING   (pinned %s)\n' "$tool" "${pinned:-any}"; return 1
  fi
  if [ -z "$pinned" ]; then
    printf '%-10s UNPINNED  (installed %s)\n' "$tool" "$installed"; return 0
  fi
  case "$installed" in
    "$pinned"|"$pinned".*) printf '%-10s OK         %s\n' "$tool" "$installed" ;;
    *) printf '%-10s MISMATCH  installed %s, pinned %s\n' "$tool" "$installed" "$pinned"; return 1 ;;
  esac
}

# Checks the given tools, returning non-zero if any is missing or at a different version than pinned.
check_tool_versions(){
  local rc=0 tool
  for tool in "$@"; do
    check_tool_version "$tool" || rc=1
  done
  return $rc
}

# The tools relevant to the cloud targeted: all the clouds' if CLOUD is empty.
pinned_tools(){
  echo git java mvn gh docker tofu kubectl jq yq helm hubble kind cosign
  if [ -z "$CLOUD" ] || [ "$CLOUD" == "aws" ]; then echo aws eksctl; fi
  if [ -z "$CLOUD" ] || [ "$CLOUD" == "azure" ]; then echo az kubelogin; fi
  if [ -z "$CLOUD" ] || [ "$CLOUD" == "gcp" ]; then echo gcloud; fi
}

# When run rather than sourced, prints "<tool>=<version>" for each tool given, for the platform (linux or macos) first:
#   bash pinned.sh linux mvn docker
if [ "${BASH_SOURCE[0]}" == "$0" ]; then
  PINNED_PLATFORM=$1
  shift
  for tool in "$@"; do
    echo "$tool=$(pinned_version "$tool")"
  done
fi
