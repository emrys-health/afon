# Setup Local Env

## Bash 

Env setup requires *bash* installed on your machine.  

For linux and macOS bash is provided.  

To set up on a given OS, run: 

```shell
afon-cli.sh local-setup -c <cloud>
```

The following packages are installed:
- git
- jdk
- maven
- docker
- opentofu
- kubernetes-cli
- jq
- yq
- helm
- awscli eksctl
- azure-cli kubelogin
- google-cloud-cli gke-gcloud-auth-plugin

Of the cloud cli packages, only the ones relative to the `<cloud>` specified are installed.
If no `<cloud>` is specified all are installed.

**NOTE:** JDK 17 is currently enforced by the Maven build.

To show what is currently installed, checked against the pinned versions:
```shell
afon-cli.sh local-versions -c <cloud>
```

The tool versions are pinned in `scripts/local/pinned.sh`, to make local setups reproducible.
`local-setup` installs the pinned version where it can:
- apt: installs `package=<pinned version>` (the newest available version starting with the pin) and holds it with `apt-mark hold`.
  Docker's, Google's and Azure's apt repos are added if missing;
- brew: installs the formula and runs `brew pin` if it is at the pinned version, otherwise warns.
  Brew only offers the current version of a formula, so use a versioned formula (eg `openjdk@17`) or a tap if an older one is needed;
- release binaries, on both linux and macOS, for the tools that apt or brew do not offer at the pinned version:
  kubectl, tofu, hubble, yq, helm, eksctl, kubelogin, the aws cli, and on macOS also jq and the google cloud sdk.
  Each is installed in `/usr/local/bin` (override with `BIN_DIR`), only if not already at the pinned version.
  A brew copy of the same tool earlier on the PATH shadows it: `local-setup` warns, so `brew uninstall` it.

It ends by checking the installed versions against the pins.
`local-versions` reports each tool as `OK`, `MISMATCH`, `MISSING` or `UNPINNED`.
A pin is a version prefix, eg `17` for java matches any 17.x.
As apt and brew offer different versions, a pin can be overridden per platform, eg `PINNED_git_linux`.
On linux docker is installed as `docker-ce`, from Docker's apt repo.

## Docker 

To build the Docker container, run:
```shell
cd scripts/docker/afon
docker build -t emryshealth/afon .
```

To run the Docker container, link your afon-core dir to $HOME/afon-core and use following docker command:
```shell
ln -s <your afon-core dir> $HOME/afon-core
docker run -it -v $HOME/.kube:$HOME/.kube -v $HOME/.config/gcloud:$HOME/.config/gcloud -v $HOME/.azure:$HOME/.azure -v $HOME/.aws:$HOME/.aws  -v $HOME/afon-core/:$HOME/afon-core  -v $HOME/.m2:$HOME/.m2 -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) -e HOST_USER=$(whoami)  emryshealth/afon