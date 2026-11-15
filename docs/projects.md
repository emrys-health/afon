# Projects

A project is what Afon deploys together for one purpose, defined by a `project.yaml` that the deployer writes. This page is the shortest
path to a working end-to-end deployment on Azure, from the env `emrys2`, with two projects:

- `central-identity`, the project with the role `identity`: the one Keycloak that signs users in for every project;
- `project1`, a project with the role `tre` (the default): a trusted research environment that uses that Keycloak.

Each project is made from an env, here `emrys2`: its config is a copy of the env's, with what `project.yaml` says written over it, so where it
deploys (the subscription, resource group, region, domain names, secrets) is the env's, not the file's. A project has its own config, state
and home, so running it never switches the env you are working in:

```
~/.afon/projects/<project>/project.yaml    what you write
~/.afon/projects/<project>/keys/           the public keys of its clients (a TRE project)
~/.afon/projects/<project>/home/           the Afon home that its commands run in, made by `configure`
~/.afon/config-<project>/                  its config, made by `configure` from the env
~/.afon/cdktf-<project>/                   its stacks, tofu state and kubeconfigs
```

A project id is a lower-case DNS label of at most 30 characters, eg `xyz-yyyy-nnnn`.

## Before you start

You are logged in to Azure, and `emrys2` is the active env:

```shell
afon-cli.sh azure-login
afon-cli.sh --env emrys2 config-status
```

The config of `emrys2` already has what both projects use: `AZURE_SUBSCRIPTION`, `AZURE_RESOURCE_GROUP`, `AZURE_REGION`,
`AZURE_OIDC_DOMAIN_NAME` (`azure-oidc.emrys2.click`), `AZURE_JUPYTER_DOMAIN_NAME` (`azure-jupyter.emrys2.click`), `POLARIS_DB_HOST`, and the
Keycloak admin credentials in `secret.properties`.

## 1. The central identity

**1. Reserve its egress IP.** The identity reaches its database from an address that must not change when it is rebuilt, so that the database
allows it once:

```shell
afon-cli.sh azure-egress-ip create afon-egress-ip-central-identity
afon-cli.sh azure-egress-ip show afon-egress-ip-central-identity
```

The script ends with a line about `AZURE_EGRESS_IP` in `afon-azure.properties`: ignore it, as the project carries its own address.

**2. Make `~/.afon/projects/central-identity/project.yaml`** from the template of its role, and complete it with the address from step 1:

```shell
afon-cli.sh manage-project init central-identity azure --role identity --made-from emrys2
```

`--made-from` is the env whose config the project is made from. It is not `--env`, which `afon-cli.sh` takes itself, and which switches your
active env. `init` never overwrites a file, unless given `--force`, which keeps the old one beside it as `project.yaml.<time>.bak`.

What `init` leaves in `<angle brackets>` is what only you know, so `validate` fails until it is replaced. Here that is the egress IP, which you
replace with the address from step 1. The result is:

```yaml
project:
  id: central-identity
  cloud: azure
  role: identity
  env: emrys2
  egressIp: <the address from step 1>

cluster:
  nodePool:
    vmSize: Standard_D2s_v3
    nodeCount: 2
```

`cluster.nodePool` gives the nodes of the identity's cluster, which runs Keycloak only, so that it does not take the vCPU quota that the larger
nodes of `azure-full` need. It is optional: without it the project uses the nodes of the env, and both clusters then take the same size.

**3. Check it and configure it:**

```shell
afon-cli.sh manage-project validate central-identity
afon-cli.sh manage-project configure central-identity
```

**4. Bring it up**, and check that Keycloak answers:

```shell
afon-cli.sh manage-project up central-identity --dry-run
afon-cli.sh manage-project up central-identity
afon-cli.sh azure-keycloak
```

`up` runs two stages in the project's home: `create` (the `azure-create` script for the identity's stack, then `db-whitelist azure`, which allows the project's egress IP on the Postgres at `POLARIS_DB_HOST`) and `identity` (`manage-stack setup azure-keycloak`). It stops at the first to fail and says how to resume: `manage-project up central-identity --from <stage>`.

## 2. A TRE project

**1. Make the key pair of its TRE client.** The client authenticates to the identity with a private key, and the identity is given the public
key. This writes the public key to `~/.afon/projects/project1/keys/tre.jwk`, and the private key, readable by its owner only, to
`~/.afon/keys/project1/tre.pem`:

```shell
afon-cli.sh project-key project1 tre
```

Keep the private key in the secret store of the TRE's cloud, and delete that copy: Afon never reads it.

**2. Make `~/.afon/projects/project1/project.yaml`** from the template of its role, which is `tre` unless you give `--role identity`, and
complete it:

```shell
afon-cli.sh manage-project init project1 azure --made-from emrys2
```

It has the minimum a TRE project needs:

- the issuer of the identity;
- the `acr` (authentication context class reference) value that proves a second factor;
- one Data Provider catalog, here the Polaris of the env itself;
- an audit bucket;
- the identity project that deploys the identity;
- the domain that its TRE is served at.

```yaml
project:
  id: project1
  cloud: azure
  env: emrys2

identity:
  project: central-identity
  issuerUrl: https://azure-oidc.emrys2.click/realms/polaris
  acr: mfa

providers:
  - id: azure
    catalogUri: https://azure-polaris.emrys2.click/api/catalog
    catalogName: azure
    audience: provider-azure
    bucket: emrys2afonstorage

cluster:
  nodePool:
    vmSize: Standard_D4s_v6
    nodeCount: 2

stacks:
  create:
    auditBucket: project1-audit
  tre:
    domains:
      AZURE_JUPYTER_DOMAIN_NAME: azure-jupyter.emrys2.click
```

The TRE has a larger `cluster.nodePool`, as `azure-full` does not fit smaller nodes: two `Standard_D4s_v6` are 8 vCPUs of the
`StandardDsv6Family`, so mind the quota of the subscription, which the identity's cluster, and any other, share. On GCP the keys are
`machineType` and `nodeCount`; AWS has no node pool property, so a project there takes the nodes of its env.

The TRE deploys the stack `azure-full`, with Git in it, and is not rebuilt at night. It leaves from the egress IP that `emrys2` has, which is already allowed on the database, so there is nothing to reserve or whitelist for it.

**3. Check it, configure it and bring it up:**

```shell
afon-cli.sh manage-project validate project1
afon-cli.sh manage-project plan project1
afon-cli.sh manage-project configure project1
afon-cli.sh manage-project up project1 --dry-run
afon-cli.sh manage-project up project1
```

`up` runs three stages in the project's home: `create` (`azure-create` for `azure-full`), `register` (`manage-identity create project1`: the project's client and group in the central identity, which needs the identity to be up; see below) and `tre` (`manage-stack setup azure-full`). If one fails, fix the cause and resume with `manage-project up project1 --from <stage>`.

Because it uses the names and the egress IP of `emrys2`, do not deploy `emrys2`'s own `azure-full` at the same time.

`manage-identity` calls Keycloak's admin API, which the ingress of the identity may admit only the addresses of
`AZURE_KEYCLOAK_ALLOWED_CIDRS`, which need not include your machine. Because the project names its identity project, `up` writes the
kubeconfig of the identity's cluster (`azure-update-kube-config`, in the identity project's home) and runs `manage-identity create` with
`--kubeconfig` of it. It then reaches Keycloak through a port-forward to its pod, which lives as long as the command. No address is admitted to
anything, so there is nothing to take off afterwards, and it works whatever address your machine leaves from.

The `tre` stage configures that Keycloak too, for the TRE's stacks, again through a port-forward. `configure` writes `KEYCLOAK_KUBECONFIG` into
the project's config, naming the kubeconfig of the identity project's cluster, so that the port-forward goes there and not to the TRE's own
cluster, which has no Keycloak. For that, make the identity project before you `configure` the TRE project, and `up` writes the kubeconfig
again before the `tre` stage.

## Running a command in a project

`manage-project run` runs a command of Afon in the home of a project, so that you need not type `--home`. Everything after the project is the command, as it is, and in the cloud of the project, so that a command that needs one, such as `cdktf-store`, need not be given `--cloud`, which `afon-cli.sh` would take for itself:

```shell
afon-cli.sh manage-project run central-identity kube-keycloak-whitelist azure show
afon-cli.sh manage-project run project1 azure-kubectl get pods -A
afon-cli.sh manage-project run project1 cdktf-store list
```

The options that `afon-cli.sh` takes itself (`--env`, `--cloud`, `--home`, `--cdktf`) cannot be given to the command, which is why the cloud
is the project's. In a project's home, `manage-identity` also needs no project id and reaches Keycloak through the port-forward that
`configure` named, so run it there, never in your own env, whose ingress refuses your machine:

```shell
afon-cli.sh manage-project run project1 manage-identity add-member <email>
afon-cli.sh manage-project run project1 manage-identity remove-member <email>
```

The state of a project's stacks is stored by `cdktf-store`, as the tag `current`, under the project's id, so apart from that of any env.

## Taking them down

```shell
afon-cli.sh manage-project down project1
afon-cli.sh manage-project run project1 manage-identity close
afon-cli.sh manage-project down central-identity
```

`down` deletes the stacks, the last first. The reserved IP, the buckets and the projects' config and cdktf dirs are left, as an address may be
on somebody's allow-list. `manage-identity close`, run in the project's home, where it needs no project id, as the config has it, so that it reaches Keycloak through the port-forward that `configure`
named (`KEYCLOAK_KUBECONFIG`), as `manage-identity` run in another config would be refused by the ingress, disables the project's TRE client, so that no token is issued for it. Its group and members stay. To open the project again, run `create`
in its home, which makes the client again, enabled:

```shell
afon-cli.sh manage-project run project1 manage-identity create
```

If the central identity is brought down too, its state is lost with it, so after bringing it up again run `register` for each project:
`manage-project up <project> --from register`.

## What this does not do yet

- The users of a project are not configured by the project: add them by hand, `manage-identity add-member`, as above.
- The TRE's tools do not yet sign in with the project's client, nor check the audience, the project claim or the `acr` of a token: the
  project's client and group exist in Keycloak, and the TRE works as a normal TRE that uses it.
- Keycloak does not issue the `mfa` level yet.
- The Data Provider catalog in `providers` is checked, but not yet read by the engines.

The design, and the options this page leaves out (a project's own egress IP, its own Git, its nightly rebuild, members), are in
`multi_project.md` in the `afon-dev-docs` repository.
