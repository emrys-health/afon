# Setup Local Env

## Bash 

Env setup requires *bash* installed on your machine.  

For linux and macOS bash is provided.  

To set up on a given OS, run: 

```shell
./afon.sh -a setup-local -c <cloud>
```

The following packages are installed:
- git
- jdk
- maven
- docker
- opentofu
- kubernetes-cli
- awscli
- eksctl
- azure-cli
- google-cloud-cli

Of the cloud cli packages, only the ones relative to the `<cloud>` specified are installed.
If no `<cloud>` is specified all are installed.

**NOTE:** JDK 17 is currently enforced by the Maven build.

To show what is currently installed:
```shell
./afon.sh -a setup-show -c <cloud>
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