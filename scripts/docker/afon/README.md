# Motive

Here we create a Docker container that allows the user to use the right version of all CLI tools.

# Usage

To build the Docker container, run:
```shell
docker build -t emryshealth/afon .
```

To run the Docker container, link your afon dir to $HOME/afon and use following docker command:
```shell
ln -s <your afon dir> $HOME/afon
docker run -it -v $HOME/.kube:$HOME/.kube -v $HOME/.config/gcloud:$HOME/.config/gcloud -v $HOME/.azure:$HOME/.azure -v $HOME/.aws:$HOME/.aws  -v $HOME/afon/:$HOME/afon  -v $HOME/.m2:$HOME/.m2 -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) -e HOST_USER=$(whoami)  emryshealth/afon
```
