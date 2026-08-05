# Env setup

## Bash 

Env bash setup requires *bash* installed on your machine.  

For linux and macos bash is provided.  

For windows, use Git Bash bundled with [Git for Windows](https://github.com/git-for-windows/git/releases/latest).

To setup on a given OS, run: 

```shell
./afon.sh -a setup
```

The following packages are installed:
- git
- jdk
- maven
- docker
- opentofu
- kubernetes-cli
- {aws, azure, gcp}-cli

NB: JDK 17 is currently enforced by the Maven build.

To show what is currently installed:
```shell
./afon.sh -a setup --setup-show
```

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