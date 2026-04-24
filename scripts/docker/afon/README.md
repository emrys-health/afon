# Motive

Here we create a Docker container that allows the user to use the right version of all CLI tools.

# Usage

To build the Docker container, run:
```shell
docker build -t ph1ll1phenry/afon .
```

To run the Docker container, run:
```shell
docker run -it  -v $HOME/.aws:$HOME/.aws  -v $HOME/Code/Emrys/afon-core/:$HOME/afon-core  -v $HOME/.m2:$HOME/.m2 -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) -e HOST_USER=$(whoami)  ph1ll1phenry/afon
```
Note that the `$HOME/Code/Emrys/afon-core/` should point to where you have your `afon-core` code.