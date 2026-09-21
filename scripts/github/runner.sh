# GibHub actions runner
# ./runner.sh {install|configure|service}
#
# Driven by hand, or by the 'github' role of the ansible playbook in scripts/ansible,
# which passes the settings below as environment variables. Every one of them keeps
# its usual value when unset, except the registration token, which is short lived and
# so has to be supplied per run:
#
#   RUNNER_TOKEN   registration token from
#                  https://github.com/emrys-health/afon-core/settings/actions/runners/new
#                  (or: gh api --method POST /repos/emrys-health/afon-core/actions/runners/registration-token)
#   RUNNER_ARGS    extra config.sh flags, e.g. '--unattended --name linux-ec2 --replace'
#   RUNNER_DIR     where the runner is installed.  Defaults to './actions-runner'
#   RUNNER_URL     the repo to register with
#   RUNNER_SHA256  checksum of the runner package.  Set empty to skip the check
#   VERSION        the runner version

install(){
  # Create a folder
  mkdir -p $RUNNER_DIR && cd $RUNNER_DIR
  # Download the latest runner package
  curl -o actions-runner-linux-x64-$VERSION.tar.gz -L https://github.com/actions/runner/releases/download/v$VERSION/actions-runner-linux-x64-$VERSION.tar.gz
  # Optional: Validate the hash
  if [[ -n "$RUNNER_SHA256" ]]; then
    echo "$RUNNER_SHA256  actions-runner-linux-x64-$VERSION.tar.gz" | shasum -a 256 -c
  fi
  # Extract the installer
  tar xzf ./actions-runner-linux-x64-$VERSION.tar.gz
}

configure(){
  # Create the runner and start the configuration experience
  cd $RUNNER_DIR
  if [[ -z "$RUNNER_TOKEN" ]]; then
    echo "Action configure requires RUNNER_TOKEN.  Registration tokens expire after an hour, so"
    echo "take a fresh one from $RUNNER_URL/settings/actions/runners/new"
    exit 1
  fi
  ./config.sh --url $RUNNER_URL --token $RUNNER_TOKEN $RUNNER_ARGS
  sudo ./svc.sh install
}

service(){
  # service the runner
  cd $RUNNER_DIR
  sudo ./svc.sh $SERVICE
}

ACTION="${1:-}"
SERVICE="${2:-status}"
VERSION="${VERSION:-2.336.0}"

RUNNER_DIR="${RUNNER_DIR:-actions-runner}"
RUNNER_URL="${RUNNER_URL:-https://github.com/emrys-health/afon-core}"
RUNNER_TOKEN="${RUNNER_TOKEN:-}"
RUNNER_ARGS="${RUNNER_ARGS:-}"
RUNNER_SHA256="${RUNNER_SHA256:-04cf0be1aff4c3ec3554466c39124ca250e3effd8873bb7e8d68535aa9505d5d}"

if [[ -z "$ACTION" ]]; then
  echo "Usage: ${BASH_SOURCE[0]} {install|configure|service} [start|stop|status]"
  exit 1
fi

$ACTION
