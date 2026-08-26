# Jupyter

## Prepare stack

We assume the AWS full stack is deployed.  See [stacks](./stacks.md) for more details.

## Login to Jupyter 

Login to [http://emrysjupyter.click](http://emrysjupyter.click) and follow the OAuth link to fire up your pod, 
authenticating with one of the users defined in `$AFON_HOME/config/users.csv`.  
The deployment of the stack will have configured these users.

Open application bash on pod.  Launch firefox.  Open URL http://localhost:8888 on firefox.

You should see the Jupyter user pod with status `ContainerCreating` when you run 
```shell
kubectl get pods
```
It may take a minute or two if the container image is not cached.

The Jupyter user pod name can retrieved by the command:
```shell
USER_POD=$(kubectl get pods | grep <username> | awk '{print $1}')
```

## Copying notebooks to and from Jupyter user pod.

Copying notebooks to and from the Jupyter user pod:
```shell
kubectl cp <your_ipynb_path> $USER_POD:/home/jovyan
kubectl cp $USER_POD:/home/jovyan/<your_ipynb_path> . 
```
The notebooks are assumed to be in the user home of the pod (`/home/jovyan`).  
Adapt the commands as appropriate for other paths.
