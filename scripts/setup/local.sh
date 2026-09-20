SETUP_OS=$(uname -a| awk '{print $NF}')

install_eksct_linux(){
  curl -sLO "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_Linux_amd64.tar.gz" && tar -xzf eksctl_Linux_amd64.tar.gz -C /tmp && sudo mv /tmp/eksctl /usr/local/bin && rm eksctl_Linux_amd64.tar.gz
}

install_kubelogin(){
  az aks install-cli
}

case $SETUP_OS in
  linux|GNU/Linux)
    sudo apt install git openjdk-17-jdk maven docker tofu kubectl hubble
    if [ -z "$CLOUD" ]; then
      sudo apt install awscli
      install_eksct_linux
      sudo apt install azure-cli
      sudo apt install google-cloud-cli
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
    fi
    ;;
  macos|arm64)
    brew install git openjdk@17 maven docker opentofu kubernetes-cli hubble
    if [ -z "$CLOUD" ]; then
      brew install awscli eksctl
      brew install azure-cli
      brew install gcloud-cli
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
    fi
    ;;
  *)
    echo "Setup not supported for $SETUP_OS"
    ;;
esac
