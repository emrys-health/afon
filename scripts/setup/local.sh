SETUP_OS=$(uname -a| awk '{print $NF}')

install_eksct_linux(){
  curl -sLO "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_Linux_amd64.tar.gz" && tar -xzf eksctl_Linux_amd64.tar.gz -C /tmp && sudo mv /tmp/eksctl /usr/local/bin && rm eksctl_Linux_amd64.tar.gz
}

install_kubelogin(){
  az aks install-cli
}

# Needed by kubectl to authenticate to GKE.  A gcloud installed by apt has its component manager
# disabled, so on linux the plugin comes from its own package, while on macos it is a gcloud component.
install_gke_auth_plugin_linux(){
  sudo apt install google-cloud-cli-gke-gcloud-auth-plugin
}

install_gke_auth_plugin_macos(){
  gcloud components install gke-gcloud-auth-plugin --quiet
  # brew links only gcloud, bq and gsutil into its bin dir, so the plugin, installed in the SDK's own
  # bin dir, would not be on the PATH: kubectl and the afon scripts would not find it.
  ln -sf "$(gcloud info --format='value(installation.sdk_root)')/bin/gke-gcloud-auth-plugin" "$(brew --prefix)/bin/gke-gcloud-auth-plugin"
}

case $SETUP_OS in
  linux|GNU/Linux)
    sudo apt install git openjdk-17-jdk maven docker tofu kubectl hubble
    if [ -z "$CLOUD" ]; then
      sudo apt install awscli
      install_eksct_linux
      sudo apt install azure-cli
      install_kubelogin
      sudo apt install google-cloud-cli
      install_gke_auth_plugin_linux
    fi
    if [ "$CLOUD" == "aws" ]; then
      sudo apt install awscli
      install_eksct_linux
    fi
    if [ "$CLOUD" == "azure" ]; then
      sudo apt install azure-cli
      install_kubelogin
    fi
    if [ "$CLOUD" == "gcp" ]; then
      sudo apt install google-cloud-cli
      install_gke_auth_plugin_linux
    fi
    ;;
  macos|arm64)
    brew install git openjdk@17 maven docker opentofu kubernetes-cli hubble
    if [ -z "$CLOUD" ]; then
      brew install awscli eksctl
      brew install azure-cli
      install_kubelogin
      brew install gcloud-cli
      install_gke_auth_plugin_macos
    fi
    if [ "$CLOUD" == "aws" ]; then
      brew install awscli eksctl
    fi
    if [ "$CLOUD" == "azure" ]; then
      brew install azure-cli
      install_kubelogin
    fi
    if [ "$CLOUD" == "gcp" ]; then
      brew install gcloud-cli
      install_gke_auth_plugin_macos
    fi
    ;;
  *)
    echo "Setup not supported for $SETUP_OS"
    ;;
esac
