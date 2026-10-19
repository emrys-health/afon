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

# Where the release binaries are installed, using sudo if not writable.  BIN_DIR overrides it.
bin_dir(){
  echo "${BIN_DIR:-/usr/local/bin}"
}

# Installs the file as the tool's binary, or a link to it if a third argument is given, warning if another one of the tool, eg brew's, would shadow it on the PATH.
install_bin(){
  local file=$1 tool=$2 link=${3:-} dir found sudo=
  dir=$(bin_dir)
  [ -w "$dir" ] || sudo=sudo
  if [ -n "$link" ]; then $sudo ln -sf "$file" "$dir/$tool"; else $sudo install -m 0755 "$file" "$dir/$tool"; fi || return 1
  found=$(command -v "$tool")
  if [ "$found" != "$dir/$tool" ]; then
    echo "WARNING: $found shadows the pinned $tool installed in $dir: remove it, eg with 'brew uninstall $tool', or fix the PATH" >&2
  fi
}

# Installs the pinned release binary of the tool, unless it is already at the pinned version.
#   $1 tool, $2 url, $3 kind of download: raw, tgz or zip, $4 the binary within the archive, if one
# The url can use ${version}, ${os}, ${arch}, ${Os} (capitalised os) and ${arch2} (x86_64 or arm64), which are set here.
install_release_binary(){
  local tool=$1 url=$2 kind=$3 member=${4:-} os arch version Os arch2 dir
  check_tool_version "$tool" >/dev/null && return 0
  os=$(bin_os); arch=$(bin_arch) || return 1; version=$(pinned_version "$tool")
  Os="$(echo "${os:0:1}" | tr '[:lower:]' '[:upper:]')${os:1}"
  [ "$arch" == amd64 ] && arch2=x86_64 || arch2=arm64
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

# jq, from its release binary where brew would give another version than the pinned one.  On linux apt provides it.
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

# The aws cli from its own installers, as the apt package lags well behind the pinned version and brew's is the current one.
install_awscli(){
  local version dir rc
  check_tool_version aws >/dev/null && return 0
  version=$(pinned_version aws)
  dir=$(mktemp -d)
  echo "Installing aws cli $version: this requires sudo, so you may be asked for your password"
  if [ "$(bin_os)" == darwin ]; then
    curl -fsSL -o "$dir/aws.pkg" "https://awscli.amazonaws.com/AWSCLIV2-${version}.pkg" && sudo installer -pkg "$dir/aws.pkg" -target /
  else
    curl -fsSL -o "$dir/aws.zip" "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m)-${version}.zip" \
      && unzip -oq "$dir/aws.zip" -d "$dir" && sudo "$dir/aws/install" --update
  fi
  rc=$?
  rm -rf "$dir"
  return $rc
}

# The google cloud sdk on macos, from its pinned tarball, as brew offers only the current version.
# On linux it is installed by apt, and the gke auth plugin, needed by kubectl to authenticate to GKE, from its own package,
# since a gcloud installed by apt has its component manager disabled.
install_gcloud_macos(){
  local version arch sdk="${GCLOUD_HOME:-$HOME}/google-cloud-sdk"
  check_tool_version gcloud >/dev/null && return 0
  version=$(pinned_version gcloud)
  arch=$(bin_arch) || return 1
  [ "$arch" == amd64 ] && arch=x86_64 || arch=arm
  curl -fsSL "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${version}-darwin-${arch}.tar.gz" | tar -xz -C "$(dirname "$sdk")" \
    && "$sdk/install.sh" --quiet --usage-reporting=false --path-update=false --command-completion=false \
    && install_bin "$sdk/bin/gcloud" gcloud link
}

install_gke_auth_plugin_linux(){
  apt_install_pinned google-cloud-cli-gke-gcloud-auth-plugin gcloud
}

install_gke_auth_plugin_macos(){
  local sdk
  gcloud components install gke-gcloud-auth-plugin --quiet || return 1
  # The plugin is installed in the SDK's own bin dir, which is not on the PATH: kubectl and the afon scripts
  # would not find it.
  sdk=$(gcloud info --format='value(installation.sdk_root)')
  install_bin "$sdk/bin/gke-gcloud-auth-plugin" gke-gcloud-auth-plugin link
}

# Installs the apt package at the version pinned for the tool, ie the newest available version starting with the pin,
# and holds it so that upgrades leave it alone.  Falls back to the available version, with a warning, if none matches.
apt_install_pinned(){
  local package=$1 tool=$2 pinned version
  pinned=$(pinned_version "$tool")
  version=
  if [ -n "$pinned" ]; then
    version=$(apt-cache madison "$package" | awk '{print $3}' | grep -E "^(.*:)?${pinned//./\\.}" | head -1)
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
  brew install "$formula" || return 1
  if check_tool_version "$tool" >/dev/null; then
    brew list --pinned | grep -qx "${formula##*/}" || brew pin "$formula"
  else
    echo "WARNING: brew $formula is not at pinned version $(pinned_version "$tool"), not pinning it" >&2
  fi
}

# Installs each package of "package:tool" pairs with the given installer function.
install_pinned(){
  local installer=$1 pair; shift
  for pair in "$@"; do
    $installer "${pair%%:*}" "${pair##*:}"
  done
}

# Adds the apt repo, with its signing key, unless already there.  The list and keyring file names are the same as those of the
# scripts/ansible roles, since apt refuses a repo listed with two different keyrings.
#   $1 list name, $2 keyring name, $3 key url, $4 repo url, $5 repo suite and component, eg "stable" or "cloud-sdk main"
add_apt_repo(){
  local name=$1 keyring="/etc/apt/keyrings/$2.asc" key_url=$3 repo_url=$4 suite=$5
  local list="/etc/apt/sources.list.d/$name.list"
  [ -f "$list" ] && return 0
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL -o "$keyring" "$key_url" && sudo chmod a+r "$keyring" || return 1
  echo "deb [arch=$(dpkg --print-architecture) signed-by=$keyring] $repo_url $suite" | sudo tee "$list" >/dev/null
  sudo apt update
}

add_docker_repo_linux(){
  add_apt_repo docker docker https://download.docker.com/linux/ubuntu/gpg https://download.docker.com/linux/ubuntu "$(lsb_release -cs) stable"
}

add_google_repo_linux(){
  add_apt_repo google-cloud-sdk cloud.google https://packages.cloud.google.com/apt/doc/apt-key.gpg https://packages.cloud.google.com/apt "cloud-sdk main"
}

add_azure_repo_linux(){
  add_apt_repo azure-cli microsoft https://packages.microsoft.com/keys/microsoft.asc https://packages.microsoft.com/repos/azure-cli/ "$(lsb_release -cs) main"
}

case $SETUP_OS in
  linux|GNU/Linux)
    sudo apt install curl unzip lsb-release ca-certificates
    add_docker_repo_linux
    install_pinned apt_install_pinned gh:gh docker-ce:docker docker-ce-cli:docker git:git openjdk-17-jdk:java maven:mvn jq:jq
    install_hubble
    install_kubectl
    install_tofu
    install_yq
    install_helm
    if [ -z "$CLOUD" ] || [ "$CLOUD" == "aws" ]; then
      install_awscli
      install_eksctl
    fi
    if [ -z "$CLOUD" ] || [ "$CLOUD" == "azure" ]; then
      add_azure_repo_linux
      install_pinned apt_install_pinned azure-cli:az
      install_kubelogin
    fi
    if [ -z "$CLOUD" ] || [ "$CLOUD" == "gcp" ]; then
      add_google_repo_linux
      install_pinned apt_install_pinned google-cloud-cli:gcloud
      install_gke_auth_plugin_linux
    fi
    ;;
  macos|arm64)
    brew install gh
    install_pinned brew_install_pinned docker:docker git:git openjdk@17:java maven:mvn
    install_jq
    install_hubble
    install_kubectl
    install_tofu
    install_yq
    install_helm
    if [ -z "$CLOUD" ] || [ "$CLOUD" == "aws" ]; then
      install_awscli
      install_eksctl
    fi
    if [ -z "$CLOUD" ] || [ "$CLOUD" == "azure" ]; then
      install_pinned brew_install_pinned azure-cli:az
      install_kubelogin
    fi
    if [ -z "$CLOUD" ] || [ "$CLOUD" == "gcp" ]; then
      install_gcloud_macos
      install_gke_auth_plugin_macos
    fi
    ;;
  *)
    echo "Setup not supported for $SETUP_OS"
    ;;
esac

# Check the result against the pinned versions, as brew installs only its current ones.
echo "Checking installed versions against pinned ones (scripts/local/pinned.sh)"
check_tool_versions $(pinned_tools) || echo "Some tools are missing or differ from the pinned versions: install the pinned versions manually."
