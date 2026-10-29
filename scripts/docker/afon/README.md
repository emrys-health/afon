# Motive

Here we create a Docker container that allows the user to use the right version of all CLI tools.

# Usage

To build the Docker images (`base` = tooling, `cli` = base + afon-cli built from this repo), run:
```shell
scripts/docker/afon/build.sh              # both
scripts/docker/afon/build.sh cli          # cli only, needs the base image of the same version
scripts/docker/afon/build.sh --push       # also push every tag
```
The version comes from `git describe` over the `afon-*` release tags, eg `1.0-beta-7` on a release, else
`1.0-beta-7-126-ga7358e58[-dirty]`. Images are tagged with the version, the short commit and, on a clean release,
`latest`. See `build.sh --help`.

To run the Docker container, link your afon dir to $HOME/afon and use following docker command:
```shell
ln -s <your afon dir> $HOME/afon
docker run -it -v $HOME/.kube:$HOME/.kube -v $HOME/.config/gcloud:$HOME/.config/gcloud -v $HOME/.azure:$HOME/.azure -v $HOME/.aws:$HOME/.aws  -v $HOME/afon/:$HOME/afon  -v $HOME/.m2:$HOME/.m2 -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) -e HOST_USER=$(whoami)  emryshealth/afon
```
