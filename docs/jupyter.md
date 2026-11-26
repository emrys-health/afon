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

`kube-user-cp` does the same without finding the pod, on the current kubectl cluster:
```shell
afon-cli.sh kube-user-cp [--user <user>] to <local-path> [<pod-path>]
afon-cli.sh kube-user-cp [--user <user>] from <pod-path> [<local-path>]
```
A notebook that needs the member's token, `OIDC_TOKEN`, such as `project-spark-reads-data-providers` and `project-trino-reads-data-providers`, can be run
interactively in the pod of a member who is signed in to the hub, as the token is put in the pod at sign-in, or, in a project, from the CLI by `smoke-notebook`, as the smoke member.

`--user` is the username, or the start of the email, of the pod's user, and is not needed when only one notebook pod is running. A path of the pod is
relative to its home, unless it starts with `/`. In a project, run it in the project's cluster:
```shell
afon-cli.sh manage-project-stack run cvd-2026 kube-user-cp --user alice@tre.example to project-trino-reads-data-providers.ipynb
afon-cli.sh manage-project-stack run cvd-2026 kube-user-cp --user alice@tre.example from project-trino-reads-data-providers.ipynb .
```

**The pods of a project's users.** `project-pods` lists the notebook pods of a project's members, with their users, and
deletes those of one user or of all of them, which ends their servers. The hub makes a new pod as soon as a member opens
the TRE's URL again, with no new sign-in needed if they are still signed in. That gives a member a fresh pod, eg when
their remote desktop is black because its window manager died (`[fluxbox] <defunct>` in the pod's processes). What the
member has not saved in the pod is lost, so it asks first, unless given `--yes`, and it needs `--user` or `--all` to
delete:
```shell
afon-cli.sh manage-project-stack run cvd-2026 project-pods
afon-cli.sh manage-project-stack run cvd-2026 project-pods delete --user alice@tre.example
afon-cli.sh manage-project-stack run cvd-2026 project-pods delete --all --yes
```
`--dry-run` says which pods it would delete and deletes none.
