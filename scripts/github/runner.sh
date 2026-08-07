# GibHub actions runner
# ./runner.sh {install|configure|service}

install(){
  # Create a folder
  mkdir $RUNNER_DIR && cd $RUNNER_DIR
  # Download the latest runner package
  curl -o actions-runner-linux-x64-$VERSION.tar.gz -L https://github.com/actions/runner/releases/download/v$VERSION/actions-runner-linux-x64-$VERSION.tar.gz
  # Optional: Validate the hash
  echo "04cf0be1aff4c3ec3554466c39124ca250e3effd8873bb7e8d68535aa9505d5d  actions-runner-linux-x64-$VERSION.tar.gz" | shasum -a 256 -c
  # Extract the installer
  tar xzf ./actions-runner-linux-x64-$VERSION.tar.gz
}

configure(){
  # Create the runner and start the configuration experience
  cd $RUNNER_DIR
  ./config.sh --url https://github.com/emrys-health/afon-core --token AAB5PTMOWCGLYTIBVPE3XMTKOW23O
  sudo ./svc.sh install
}

service(){
  # service the runner
  cd $RUNNER_DIR
  sudo ./svc.sh $SERVICE
}

ACTION="${1:-}"
SERVICE="${2:-status}"
VERSION="2.336.0"

RUNNER_DIR="actions-runner"

if [[ -z "$ACTION" ]]; then
  echo "Usage: $0 {install|configure|service} [start|stop|status]"
  exit 1
fi

$ACTION