SETUP_OS=$(uname -a| awk '{print $NF}')

source $AFON_ROOT/scripts/local/pinned.sh

# The os and architecture of the release binaries, ie linux or darwin, and amd64 or arm64.
bin_os(){
  uname -s | tr '[:upper:]' '[:lower:]'
}

bin_arch(){
  case "$(uname -m)" in
    x86_64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    *) echo "unsupported architecture $(uname -m)" >&2; return 1 ;;
  esac
}

# True if NO_SUDO=true: sudo is never run, so the release binaries go to a directory the user can write to, and what needs apt (git, java,
# maven and the cloud clis on linux) is not installed, only reported.
no_sudo(){
  [ "${NO_SUDO:-}" == true ]
}

# Where the release binaries are installed, using sudo if not writable.  BIN_DIR overrides it; with NO_SUDO=true it is ~/.local/bin, which
# must be on the PATH.
bin_dir(){
  if [ -n "${BIN_DIR:-}" ]; then echo "$BIN_DIR"
  elif no_sudo; then echo "$HOME/.local/bin"
  else echo /usr/local/bin
  fi
}

# Installs the file as the tool's binary, or a link to it if a third argument is given, warning if another one of the tool, eg brew's, would shadow it on the PATH.
install_bin(){
  local file=$1 tool=$2 link=${3:-} dir found sudo=
  dir=$(bin_dir)
  if no_sudo; then
    mkdir -p "$dir" && [ -w "$dir" ] || { echo "ERROR: $dir is not writable and NO_SUDO=true: set BIN_DIR to a directory that is" >&2; return 1; }
  fi
  [ -w "$dir" ] || sudo=sudo
  echo "Installing $tool in $dir"
  if [ -n "$link" ]; then $sudo ln -sf "$file" "$dir/$tool"; else $sudo install -m 0755 "$file" "$dir/$tool"; fi || return 1
  found=$(command -v "$tool")
  if [ "$found" != "$dir/$tool" ]; then
    echo "WARNING: $found shadows the pinned $tool installed in $dir: remove it, eg with 'brew uninstall $tool', or fix the PATH" >&2
  fi
}

# Sets the variables used by the download urls, which the caller declares local: ${version} (pinned for the tool), ${os}
# (linux or darwin), ${Os} (capitalised), ${arch} (amd64 or arm64), ${arch2} (x86_64 or arm64) and ${arch3} (x86_64 or aarch64).
release_vars(){
  version=$(pinned_version "$1")
  os=$(bin_os); arch=$(bin_arch) || return 1
  Os="$(echo "${os:0:1}" | tr '[:lower:]' '[:upper:]')${os:1}"
  if [ "$arch" == amd64 ]; then arch2=x86_64; arch3=x86_64; else arch2=arm64; arch3=aarch64; fi
}

# Installs the pinned release binary of the tool, unless it is already at the pinned version.
#   $1 tool, $2 url, $3 kind of download: raw, tgz or zip, $4 the binary within the archive, if one
# The url and the binary can use the variables set by release_vars.
install_release_binary(){
  local tool=$1 url=$2 kind=$3 member=${4:-} os arch version Os arch2 arch3 dir found
  if check_tool_version "$tool" >/dev/null; then
    found=$(command -v "$tool")
    if [ "$found" == "$(bin_dir)/$tool" ]; then
      echo "$tool is up to date in $(bin_dir)"
    else
      echo "$tool is up to date at $found (release binaries are installed in $(bin_dir))"
    fi
    return 0
  fi
  release_vars "$tool" || return 1
  url=$(eval echo "$url")
  member=$(eval echo "$member")
  dir=$(mktemp -d)
  case $kind in
    raw) curl -fsSL -o "$dir/$tool" "$url" && member=$tool ;;
    tgz) curl -fsSL "$url" | tar -xz -C "$dir" ;;
    zip) curl -fsSL -o "$dir/download.zip" "$url" && unzip -oq "$dir/download.zip" -d "$dir" ;;
  esac && install_bin "$dir/$member" "$tool"
  local rc=$?
  rm -rf "$dir"
  return $rc
}

# The github cli.
install_gh(){
  local kind=tgz ext=tar.gz Os=linux
  [ "$(bin_os)" == darwin ] && { kind=zip; ext=zip; Os=macOS; }
  install_release_binary gh 'https://github.com/cli/cli/releases/download/v${version}/gh_${version}_'$Os'_${arch}.'$ext $kind 'gh_${version}_'$Os'_${arch}/bin/gh'
}

# The docker client.  The engine, ie docker desktop, colima or dockerd, is a prerequisite.
install_docker(){
  local path=linux
  [ "$(bin_os)" == darwin ] && path=mac
  install_release_binary docker 'https://download.docker.com/'$path'/static/stable/${arch3}/docker-${version}.tgz' tgz docker/docker
}

install_eksctl(){
  install_release_binary eksctl 'https://github.com/eksctl-io/eksctl/releases/download/v${version}/eksctl_${Os}_${arch}.tar.gz' tgz eksctl
}

# mikefarah's yq, which the afon scripts use to read YAML.  The apt package of that name is another tool, a wrapper
# around jq, so the release binary is installed instead.
install_yq(){
  install_release_binary yq 'https://github.com/mikefarah/yq/releases/download/v${version}/yq_${os}_${arch}' raw
}

# helm, which renders the charts of the stacks, eg for kube-estimator.  It is in no default apt repository.
install_helm(){
  install_release_binary helm 'https://get.helm.sh/helm-v${version}-${os}-${arch}.tar.gz' tgz '${os}-${arch}/helm'
}

# kubectl, opentofu and hubble are in no default apt repository, and brew offers only their current versions.
install_kubectl(){
  install_release_binary kubectl 'https://dl.k8s.io/release/v${version}/bin/${os}/${arch}/kubectl' raw
}

install_tofu(){
  install_release_binary tofu 'https://github.com/opentofu/opentofu/releases/download/v${version}/tofu_${version}_${os}_${arch}.zip' zip tofu
}

install_hubble(){
  install_release_binary hubble 'https://github.com/cilium/hubble/releases/download/v${version}/hubble-${os}-${arch}.tar.gz' tgz hubble
}

# kind, which runs the Kubernetes cluster of the image signing acceptance test in Docker, and cosign, which signs the images.  Neither is in a
# default apt repository, and brew offers only their current versions, so the release binaries are installed.
install_kind(){
  install_release_binary kind 'https://kind.sigs.k8s.io/dl/v${version}/kind-${os}-${arch}' raw
}

install_cosign(){
  install_release_binary cosign 'https://github.com/sigstore/cosign/releases/download/v${version}/cosign-${os}-${arch}' raw
}

# jq, from its release binary.
install_jq(){
  local jqos=$(bin_os)
  [ "$jqos" == darwin ] && jqos=macos
  install_release_binary jq "https://github.com/jqlang/jq/releases/download/jq-\${version}/jq-$jqos-\${arch}" raw
}

# kubelogin, which kubectl uses to authenticate to AKS.  `az aks install-cli` is not used as it also replaces kubectl with the
# latest version, and brew's kubelogin is another tool (int128's OIDC plugin), so Azure's pinned release binary is installed.
install_kubelogin(){
  install_release_binary kubelogin 'https://github.com/Azure/kubelogin/releases/download/v${version}/kubelogin-${os}-${arch}.zip' zip 'bin/${os}_${arch}/kubelogin'
}

# Installs the apt package at the version pinned for the tool, ie the newest available version starting with the pin,
# and holds it so that upgrades leave it alone.  Falls back to the available version, with a warning, if none matches.
apt_install_pinned(){
  local package=$1 tool=$2 pinned version
  if no_sudo; then
    check_tool_version "$tool" >/dev/null || echo "WARNING: $tool is not installed at the pinned version, and NO_SUDO=true: install the apt package $package yourself" >&2
    return 0
  fi
  pinned=$(pinned_version "$tool")
  version=
  if [ -n "$pinned" ]; then
    # grep finding no version must not stop the script (set -e, pipefail): the fallback below is for that
    version=$(apt-cache madison "$package" | awk '{print $3}' | grep -E "^(.*:)?${pinned//./\\.}" | head -1 || true)
    [ -z "$version" ] && echo "WARNING: $package has no version $pinned available in apt, installing the available one" >&2
  fi
  if [ -n "$version" ]; then
    sudo apt install "$package=$version" && sudo apt-mark hold "$package"
  else
    sudo apt install "$package"
  fi
}

# Installs the brew formula and pins it, so that upgrades leave it alone, if its version is the one pinned for the tool.
# Brew only installs the current version of a formula (or that of a versioned formula, eg openjdk@17), so a different
# version is reported, not pinned.
brew_install_pinned(){
  local formula=$1 tool=$2
  # not installed again if it is, as brew then fails if a newer version exists, which the pin holds back, while still being the one wanted
  brew list "$formula" >/dev/null 2>&1 || brew install "$formula" || return 1
  if check_tool_version "$tool" >/dev/null; then
    brew list --pinned | grep -qx "${formula##*/}" || brew pin "$formula" 2>/dev/null || echo "WARNING: brew $formula cannot be pinned, as it is a cask" >&2
  else
    echo "WARNING: brew $formula is not at pinned version $(pinned_version "$tool"), not pinning it" >&2
  fi
}

# git, java, maven and the cloud clis come from apt or brew; the cloud clis need python, which they bring with them.
#   $1 apt package, $2 brew formula or cask, $3 tool
install_package(){
  case $SETUP_OS in
    linux|GNU/Linux) apt_install_pinned "$1" "$3" ;;
    macos|arm64) brew_install_pinned "$2" "$3" ;;
  esac
}

# The gke auth plugin, needed by kubectl to authenticate to GKE, of the same version as gcloud: its own package on linux, as a gcloud
# installed by apt has its component manager disabled, and a gcloud component on macos.
install_gke_auth_plugin(){
  local sdk
  command -v gke-gcloud-auth-plugin >/dev/null && return 0
  case $SETUP_OS in
    linux|GNU/Linux) apt_install_pinned google-cloud-cli-gke-gcloud-auth-plugin gcloud ;;
    macos|arm64)
      gcloud components install gke-gcloud-auth-plugin --quiet || return 1
      # brew links only gcloud, bq and gsutil into its bin dir, so the plugin, installed in the SDK's own bin dir, would not be on
      # the PATH: kubectl and the afon scripts would not find it.
      sdk=$(gcloud info --format='value(installation.sdk_root)')
      install_bin "$sdk/bin/gke-gcloud-auth-plugin" gke-gcloud-auth-plugin link
      ;;
  esac
}

# Adds the apt repo, with its signing key, unless already there.  The list and keyring file names are the same as those of the
# scripts/ansible roles, since apt refuses a repo listed with two different keyrings.
#   $1 list name, $2 keyring name, $3 key url, $4 repo url, $5 repo suite and component, eg "stable" or "cloud-sdk main"
add_apt_repo(){
  local name=$1 keyring="/etc/apt/keyrings/$2.asc" key_url=$3 repo_url=$4 suite=$5
  local list="/etc/apt/sources.list.d/$name.list"
  [ -f "$list" ] && return 0
  no_sudo && return 0
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL -o "$keyring" "$key_url" && sudo chmod a+r "$keyring" || return 1
  echo "deb [arch=$(dpkg --print-architecture) signed-by=$keyring] $repo_url $suite" | sudo tee "$list" >/dev/null
  sudo apt update
}

add_google_repo_linux(){
  add_apt_repo google-cloud-sdk cloud.google https://packages.cloud.google.com/apt/doc/apt-key.gpg https://packages.cloud.google.com/apt "cloud-sdk main"
}

add_azure_repo_linux(){
  add_apt_repo azure-cli microsoft https://packages.microsoft.com/keys/microsoft.asc https://packages.microsoft.com/repos/azure-cli/ "$(. /etc/os-release; echo $VERSION_CODENAME) main"
}

require_commands(){
  local command missing=
  for command in "$@"; do
    command -v "$command" >/dev/null || missing="$missing $command"
  done
  if [ -n "$missing" ]; then
    echo "ERROR: local-setup requires:$missing" >&2
    return 1
  fi
}

is_linux(){
  [ "$(bin_os)" == linux ]
}

# The tools come from release downloads, with curl, except git, java, maven and the cloud clis, which come from apt or brew.
setup_tools(){
  install_package git git git
  install_package openjdk-17-jdk openjdk@17 java
  install_package maven maven mvn
  install_gh
  install_docker
  install_jq
  install_hubble
  install_kubectl
  install_tofu
  install_yq
  install_helm
  install_kind
  install_cosign
  if [ -z "$CLOUD" ] || [ "$CLOUD" == "aws" ]; then
    install_package awscli awscli aws
    install_eksctl
  fi
  if [ -z "$CLOUD" ] || [ "$CLOUD" == "azure" ]; then
    is_linux && add_azure_repo_linux
    install_package azure-cli azure-cli az
    install_kubelogin
  fi
  if [ -z "$CLOUD" ] || [ "$CLOUD" == "gcp" ]; then
    is_linux && add_google_repo_linux
    install_package google-cloud-cli gcloud-cli gcloud
    install_gke_auth_plugin
  fi
}

case $SETUP_OS in
  linux|GNU/Linux|macos|arm64)
    require_commands curl tar unzip && setup_tools
    ;;
  *)
    echo "Setup not supported for $SETUP_OS"
    ;;
esac

# Check the result against the pinned versions.
echo "Checking installed versions against pinned ones (scripts/local/pinned.sh)"
check_tool_versions $(pinned_tools) || echo "Some tools are missing or differ from the pinned versions: install the pinned versions manually."
