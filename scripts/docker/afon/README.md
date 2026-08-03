# Motive

Here we create a Docker container that allows the user to use the right version of all CLI tools.

# Usage

To build the Docker container, run:
```shell
docker build -t ph1ll1phenry/afon .
```

To run the Docker container, link your afon-core dir to $HOME/afon-core and use following docker command:
```shell
ln -s <your afon-core dir> $HOME/afon-core
docker run -it -v $HOME/.kube:$HOME/.kube -v $HOME/.config/gcloud:$HOME/.config/gcloud -v $HOME/.azure:$HOME/.azure -v $HOME/.aws:$HOME/.aws  -v $HOME/afon-core/:$HOME/afon-core  -v $HOME/.m2:$HOME/.m2 -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) -e HOST_USER=$(whoami)  ph1ll1phenry/afon
```
