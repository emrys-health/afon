# Projects

A project is what is deployed together for one purpose, defined by a `project.yaml` that the deployer writes.

We detail here a deployment from the env `emrys2`, with the central identity and two research environments on AWS, which
read the data of two Data Providers on Azure:

- `idp`, the project with the role `identity`, on AWS: the one Keycloak that signs users in for every project;
- `cvd-2026`, a project with the role `tre` (the default), on AWS: a trusted research environment that uses that
  Keycloak, and reads the `cvd` catalog of each Data Provider;
- `dbt-2026`, a second `tre` project on AWS, which runs next to `cvd-2026` and shares nothing with it but the identity,
  and reads the `dbt` catalog of each Data Provider;
- `lon` and `nw`, two projects with the role `data`, on Azure: the Data Providers, each with its own Polaris and two
  catalogs, `cvd` and `dbt`. A Data Provider is another organisation in practice, which deploys its own project: here we
  play both.

```
AWS     idp ............................ the identity: signs the members in, issues their tokens
         |
         +-- cvd-2026 (aws-full) ....... reads the catalog cvd of lon and of nw
         +-- dbt-2026 (aws-full) ....... reads the catalog dbt of lon and of nw

Azure   lon (azure-polaris) ............ catalogs cvd and dbt
        nw  (azure-polaris) ............ catalogs cvd and dbt
```

The identity (`idp`, on AWS), a Data Provider (`lon`, on Azure) and a TRE (`cvd-2026`, on AWS) have been brought up from
these files, with the identity issuing the tokens of the other two, and a member has made their account in the identity,
with a second factor, from the invitation that it emailed. What has not been shown yet is a member signing in to the TRE
and reading a catalog of the Data Provider with that sign-in: [What is not done yet](#what-is-not-done-yet) says what has
been run, and which of what is written here is the way it is meant to work and has not been tried.

Each project is made from an env, here `emrys2`: its config is a copy of the env's (partially overrideable by
`project.yaml`), so the deployment information (the account, region, domain names, secrets) come from the env. What
`configure` derives for the project replaces the env's line of the same property in the copy, where it is, so that the
lines set in terms of it, which a shell reads top down, still see it. A property that the env does not have is written in
a block at the end of the file, under a header saying so. So no file has two lines for a property, and the env's own
files are not changed. A project makes its own config, state and home, so running it never interferes with the env you
are working in:

```
~/.afon/projects/<project>/project.yaml    the project definition 
~/.afon/projects/<project>/keys/           the secrets of its clients (a TRE project)
~/.afon/projects/<project>/home/           the Afon home that its commands run in, made by `configure`
~/.afon/config-<project>/                  its config, made by `configure` from the env
~/.afon/cdktf-<project>/                   its stacks, tofu state and kubeconfigs
```

A project id is a lower-case DNS label of at most 30 characters, eg `xyz-yyyy-nnnn`.

## Before you start

You are logged in to AWS, which the identity and the TREs are on, and to Azure, which the Data Providers are on, using
`emrys2` as the active env:

```shell
afon-cli.sh --env emrys2 aws-login
afon-cli.sh --env emrys2 azure-login
```

The config of `emrys2` has to have what the projects use: `AWS_ACCOUNT_ID`, `AWS_REGION`, `AWS_OIDC_DOMAIN_NAME` (eg
`aws-oidc.emrys2.click`), `AWS_JUPYTER_DOMAIN_NAME` (eg `aws-jupyter.emrys2.click`), `POLARIS_DB_HOST`, and the Keycloak
admin credentials in `secret.properties`. For the Data Providers it also has the Azure properties `AZURE_SUBSCRIPTION`,
`AZURE_RESOURCE_GROUP`, `AZURE_REGION` and `AZURE_POLARIS_DOMAIN_NAME`, and the Polaris credentials. The parent domain,
`emrys2.click`, is a Route 53 hosted zone of the account, so that `up` can delegate the zones of the projects' domains.
The projects serve their domains under the project's id, eg the identity project `idp` at `aws-oidc.idp.emrys2.click`:
see [Names](#names-of-a-projects-resources).

## The identity project

**1. Init the project**, making the `project.yaml` file from the template of its role, choosing the name of the project, here `idp`:

```shell
afon-cli.sh manage-project init idp aws --role identity --made-from emrys2
```

`--made-from` is the env whose config the project is made from. It is not `--env`, which `afon-cli.sh` takes itself, and
which switches your active env. `init` never overwrites a file, unless given `--force`, which keeps the old one beside it
as `project.yaml.<timestamp>.bak`.

What `init` leaves in `<angle brackets>` is for the deployer to fill in, so `validate` fails until it is replaced. Here
that is the egress IP, which you reserve in the next step.

**2. Reserve its egress IP** for the project, and complete the file with it. The identity reaches its database from an
address that must not change when it is rebuilt, so that the database allows it once:

```shell
afon-cli.sh aws-egress-ip create afon-egress-ip-idp
afon-cli.sh aws-egress-ip show afon-egress-ip-idp
```

The script ends with a line about `AWS_EGRESS_IP` in `afon-aws.properties`: ignore it, as the project carries its own
address. In `~/.afon/projects/idp/project.yaml`, replace the egress IP with the address that `show` prints, and set
`dns.delegation` to `route53`: the template has `none`, with which `up` makes the zone of the project's domain but does
not delegate it, so the domain does not resolve and Keycloak cannot get its certificate. The result is:

```yaml
project:
  id: idp
  cloud: aws
  role: identity
  env: emrys2
  egressIp: <the address of idp>

dns:
  delegation: route53
```

AWS has no node pool property, so the nodes of the identity's cluster, as those of the TREs', are those of the env.
`delegation: route53` has `up` write the `NS` records of the project's domains into the Route 53 hosted zone of
`emrys2.click`: see [Automatic delegation](#automatic-delegation). It must be set, in every project's file.

**3. Check it, see its stages, and configure it:**

```shell
afon-cli.sh manage-project validate idp
afon-cli.sh manage-project plan idp
afon-cli.sh manage-project configure idp
```

`plan` lists the stages that `up` will run, and the state of each stack.

**4. Bring it up**, and check that Keycloak answers:

```shell
afon-cli.sh manage-project up idp --dry-run
afon-cli.sh manage-project up idp
afon-cli.sh aws-keycloak
```

`up` runs two stages in the project's home: `create` (the `aws-create` script for the identity's stack, then
`db-whitelist aws`, which allows the project's egress IP on the Postgres at `POLARIS_DB_HOST`, the zone of its domain,
made by `dns-create-zones` and delegated by `dns-delegate --route53`, and the ACM certificate of the domain, issued by
`aws-issue-cert --stack aws-keycloak`, which needs the zone delegated) and `identity` (`manage-stack setup aws-keycloak`,
which looks the certificate up). The certificate is issued only where `up` delegates, ie with `delegation: route53`, as
it is validated in the delegated zone; with `manual`, issue it yourself, after you delegate. It stops at the first to
fail and says how to resume: `manage-project up idp --from <stage>`.

## The data projects

A Data Provider holds data, and lets a project's TRE read it. Each runs one Polaris, whatever the number of projects,
with a catalog for each use of its data. Here `lon` and `nw` each have two catalogs, `cvd` and `dbt`: `cvd-2026` reads
the `cvd` catalog of both, and `dbt-2026` the `dbt` catalog of both. A Data Provider is a project with the role `data`,
which deploys the stack `azure-polaris` and creates its catalogs. It is long-lived, as the data it holds: it is not taken
down with a TRE, and not rebuilt.

You are logged in to Azure and the config of `emrys2` has the Azure properties that a Data Provider uses, as [Before
you start](#before-you-start) says. Bring up [the identity project](#the-identity-project) first: a Data Provider
trusts it, and its setup reaches its Keycloak.

**1. Init the project**, making the `project.yaml` file from the template of its role, choosing the name of the project, here `lon`:

```shell
afon-cli.sh manage-project init lon azure --role data --made-from emrys2
```

**2. Reserve its egress IP**, as for the identity, so that it leaves from an address of its own, named after the
project, and complete the `<fields>` of `~/.afon/projects/lon/project.yaml`, with the address as `egressIp` and the
catalogs, and set `dns.delegation` to `route53`, as the template has `none`:

```shell
afon-cli.sh azure-egress-ip create afon-egress-ip-lon
afon-cli.sh azure-egress-ip show afon-egress-ip-lon
```

```yaml
project:
  id: lon
  cloud: azure
  role: data
  env: emrys2
  egressIp: <the address of lon>

identity:
  project: idp
  issuerUrl: https://aws-oidc.idp.emrys2.click/realms/polaris
  acr: mfa
  audience: provider-lon

catalogs: [cvd, dbt]

dns:
  delegation: route53

cluster:
  nodePool:
    vmSize: Standard_D2s_v3
    nodeCount: 2
```

`identity` is the central identity that its Polaris trusts, the same that the TREs sign in to, here the one that `idp`
deploys on AWS: its issuer, the `acr` that proves a second factor, and the audience that the tokens for this Data
Provider carry, `provider-lon` if the file gives none. `configure` makes the host of the issuer the Data Provider's OIDC
domain, so that its Polaris, and the setup of its stack, use that Keycloak, and writes the kubeconfig of `idp`'s cluster
for the port-forward that the setup reaches Keycloak by, whichever cloud `idp` is on. `catalogs` lists the ids of its
catalogs, at least one: lower-case letters, digits and underscores, the first a letter. Each is an internal catalog,
named by its id, in storage that is the project's own. A Data Provider has no clients, group or members in the central
identity: that is for the TRE projects. `cluster.nodePool` is the small nodes of the identity, as a Data Provider runs
Polaris only; AWS has none.

**Its catalogs are its own.** The setup of a stack makes the catalogs that the config lists for every cloud, not just
the stack's, so a Data Provider takes none of the env's: its project's cloud lists `cvd` and `dbt`, and the other clouds
list none.

**Who can reach its Polaris.** A Data Provider does not list the TREs that read it: they name it, in their own
`dataProviders.named`. So `configure lon` looks through the projects of the Afon home for the TREs that name a catalog of
`lon`, and adds the egress IP of each, the one its file names or else the env's `<CLOUD>_EGRESS_IP`, to
`<CLOUD>_POLARIS_ALLOWED_CIDRS` in the config of `lon`, after what the env already allows there. The deploy, and
`kube-polaris-whitelist`, then restrict the Polaris ingress to those addresses and the cluster's own, and, when
`kube-polaris-whitelist` is told to (`--caller`), the machine it is run from. With no TRE naming `lon`, the property is
left as the env has it, and a Polaris with none is open, guarded by its token checks alone, as any other is. `configure`
refuses if a TRE that names it leaves from no known address, as it would be locked out with nothing to say so. A TRE that
is added or moved is admitted by configuring and deploying the Data Provider again. The storage account of the catalogs
has no network restriction yet: it is open to any network, and protected by its credentials.

Its Polaris is at the Polaris domain of the env with the project's id as a label after the first, eg the env's
`AZURE_POLARIS_DOMAIN_NAME=azure-polaris.emrys2.click` gives `azure-polaris.lon.emrys2.click`, with a database and a
storage account of its own: see [Names](#names-of-a-projects-resources). The env's domain must have a parent domain, or
the file names its own, in `stacks.data.domain`.

**3. Check it, see its stages, configure it and bring it up**, and check that its Polaris is serving:

```shell
afon-cli.sh manage-project validate lon
afon-cli.sh manage-project plan lon
afon-cli.sh manage-project configure lon
afon-cli.sh manage-project up lon --dry-run
afon-cli.sh manage-project up lon
afon-cli.sh manage-project run lon azure-polaris
```

`up` runs two stages in the project's home: `create` (`azure-create` for the stack `azure-polaris`, `db-whitelist azure`,
which allows the project's egress IP on the Postgres, and the zone of its domain, delegated by `route53`) and `data`
(`manage-stack setup azure-polaris`, which also creates the catalogs, after `aws-update-kube-config aws-keycloak` has
written the kubeconfig of `idp`'s cluster, in its own home). `down lon` deletes the stack and leaves its storage, with
the data of its catalogs.

`azure-polaris` checks each layer, run in the Data Provider's home so that it uses its config: the Azure login, the AKS
cluster and its nodes, the Polaris pod, the ingress's external IP, the TLS secret, and then real HTTPS requests to its
domain, anonymous and as the Polaris admin (`POLARIS_ID`). The last one gets a token from the central identity and calls
this Polaris with it, so it shows that the audience and `acr` that the Data Provider requires are in that client's token.

**Repeat steps 1 to 3 for `nw`**, the second Data Provider: the same commands, with `nw` for `lon`, its own egress IP
(`afon-egress-ip-nw`), its own audience (`provider-nw`) and its own catalogs, which here are also `cvd` and `dbt`:

```shell
afon-cli.sh manage-project init nw azure --role data --made-from emrys2
afon-cli.sh azure-egress-ip create afon-egress-ip-nw
afon-cli.sh azure-egress-ip show afon-egress-ip-nw
# complete ~/.afon/projects/nw/project.yaml as for lon, with that address, then:
afon-cli.sh manage-project validate nw
afon-cli.sh manage-project plan nw
afon-cli.sh manage-project configure nw
afon-cli.sh manage-project up nw --dry-run
afon-cli.sh manage-project up nw
afon-cli.sh manage-project run nw azure-polaris
```

A Data Provider holds its own catalogs and no others: `configure` empties the catalog ids of the clouds other than the
project's, which the env lists for its own stacks. Polaris keeps its catalogs in its database, which survives a rebuild,
so catalogs that an earlier deployment made stay: delete one through Polaris's own API (its catalog roles other than
`catalog_admin` first, then `DELETE /api/management/v1/catalogs/<name>`), as `manage-polaris-catalogs delete` looks a
catalog up in the config, which no longer lists it.

**4. Grant a project its catalog** (once its TRE exists, as it needs its members, and for `nw` the same as for `lon`). A
Data Provider lets a project in by its own grants, in its own Polaris, with its own admin credentials: the TRE side never
does. In the home of the Data Provider's project, for the `cvd` catalog of `lon` and the project `cvd-2026`, whose
members are read from the project's own `members.csv`, which `manage-identity` writes as it adds and removes them (see
[Members](#members)), so no user id is copied by hand:

```shell
afon-cli.sh manage-project run lon manage-project-grants grant cvd-2026 cvd
```

The command reaches the Data Provider's Polaris through a port-forward to its pod, as the setup steps do
(`POLARIS_ACCESS`, `port-forward` by default), so this machine need not be among the addresses that its ingress admits;
with `POLARIS_ACCESS=https` it calls Polaris at its public domain name, and the machine must be admitted.

It makes, for the project, a principal role `cvd-2026Project` and a catalog role `cvd-2026Reader` in the catalog, with
read-only access to the namespace `released` (or the one given with `--namespace`), and nothing else: no write, and not
`staging`, where the Data Provider makes and checks its tables. It makes a principal `afon:<user id>` for each member
in the file, who then has the project's role. The file is the authority, as `members` is: a member not in it loses
the role, and a principal with no role left is deleted. It is safe to repeat. The same command changes one member or
ends the access, or moves a table that was checked from `staging` to `released`:

```shell
afon-cli.sh manage-project run lon manage-project-grants add-member cvd-2026 <user id>
afon-cli.sh manage-project run lon manage-project-grants remove-member cvd-2026 <user id>
afon-cli.sh manage-project run lon manage-project-grants release cvd patients
afon-cli.sh manage-project run lon manage-project-grants revoke cvd-2026 cvd
```

Because the names are the allowlist, a token whose project claim or user claim names no role or principal that exists
reads nothing, as its Polaris checks the tokens (see [What is not done yet](#what-is-not-done-yet)). A
project whose catalog is its own, as `cvd` is `cvd-2026`'s, is isolated by the catalog; one catalog shared by several
projects is only isolated by the namespace of each, `--namespace`, so give each its own.

A TRE names the catalogs of a Data Provider in its `dataProviders`, as [below](#a-first-tre-project): it takes the
**catalog endpoint** (`https://azure-polaris.lon.emrys2.click/api/catalog`), the **audience** that its tokens carry for
it (`provider-lon`) and the **storage account** of the catalog from the Data Provider's project. The Data Provider
decides what a project reads: it lets a project in by its own grants, which are made with `manage-project-grants`; who
the project's members are is the TRE's, from its `members.csv` (see below, and [What is not done
yet](#what-is-not-done-yet) for what has been run).

## A first TRE project

**1. Init the project**, making the `project.yaml` file from the template of its role, which is by default `tre` unless you give a different `--role` flag, choosing the name of the project, here `cvd-2026`:

```shell
afon-cli.sh manage-project init cvd-2026 aws --made-from emrys2
```

**2. Reserve its egress IP** for the project, as for the identity, so that it leaves from an address of its own, and the
env's `AWS_EGRESS_IP` stays free for a deployment of the env itself. One address serves the outbound traffic of one
cluster, so projects that run next to each other each need their own. A Data Provider allows this address too, to let the
TRE read its data:

```shell
afon-cli.sh aws-egress-ip create afon-egress-ip-cvd-2026
afon-cli.sh aws-egress-ip show afon-egress-ip-cvd-2026
```

Then complete the `<fields>` of the file, with the address as `egressIp`, and set `dns.delegation` to `route53`, as the
template has `none`, which leaves the domains of the project undelegated. The file has the minimum a TRE project needs:

- the issuer of the identity;
- the `acr` (authentication context class reference) value that proves a second factor;
- the Data Provider catalogs, one entry each: here the `cvd` catalog of `lon`, and that of `nw`;
- an audit bucket;
- at least one member;
- the identity project that deploys the identity.

```yaml
project:
  id: cvd-2026
  cloud: aws
  env: emrys2
  egressIp: <the address of cvd-2026>

identity:
  project: idp
  issuerUrl: https://aws-oidc.idp.emrys2.click/realms/polaris
  acr: mfa

dataProviders:
  named:
    - project: lon
      catalogs: [cvd]
    - project: nw
      catalogs: [cvd]

members:
  - alice@tre.example

stacks:
  create:
    auditBucket: cvd-2026-audit

dns:
  delegation: route53
```

Each entry of `named` names a Data Provider's project, `lon` or `nw`, and its catalogs, whose files and config say the
rest: the endpoint of its Polaris (`https://azure-polaris.lon.emrys2.click/api/catalog`), the audience that the tokens
for it carry (`provider-lon`, that of its identity), the storage account of each catalog, which the TRE's whitelist
allows, and the cloud of that storage, which is not the project's here. `validate`, `configure` and `up` read them from
the Data Provider's project, which has to be made, with the config of its env there, though not yet up; they refuse a
project or catalog that is not there, and a Data Provider that trusts another identity than the one the TRE uses. Each
catalog is an entry in the TRE, with the id `<project>-<catalog>`: a TRE that reads both catalogs of `lon` names them
together, `- {project: lon, catalogs: [cvd, dbt]}`, and has `lon-cvd` and `lon-dbt`. `configure` writes the catalogs in
full, as `defined`, into the copy of the file in the project's config, as the record of what was deployed. It also writes
them into the project's config as catalogs, like any other, in the `catalogs-<cloud>.properties` of the cloud of their
storage: each id, which is that of the entry with its hyphens as underscores, eg `lon_cvd`, is in `<CLOUD>_CATALOG_IDS`
with the env's, and has `<CLOUD>_CATALOG_NAME_<id>`, `_CATALOG_URI_<id>`, `_CATALOG_AUDIENCE_<id>` and
`_BUCKET_NAME_<id>`, and `<CLOUD>_CATALOG_SOURCE_<id>=data-provider`, which says it is served from the Data Provider's
Polaris and not from the stack's own, as the default, `stack`, says of every other.
`AfonCatalogs.bySource("data-provider")` returns them, and `AfonCatalogs.all()` is what it was, the stack's own, which is
what its Polaris makes and its engines query. The scripts that make and delete the buckets of the stack's catalogs,
`<cloud>-create` and `<cloud>-destroy`, skip a catalog of the Data Provider source, as does `config-validate` when it
checks for catalogs that share a bucket, so that none of them makes or deletes the storage of a Data Provider. A config
outside a project has none.

A Data Provider that Afon did not deploy has no project to name: its catalog is then given in full, in `defined`, as
`id`, `catalogUri`, `catalogName`, `audience`, `bucket` and `cloud`. The `audience` is one for each Data Provider in the
first pass, so that the `cvd` and `dbt` catalogs of `lon` share `provider-lon`; `bucket` is the storage account of the
catalog, for the TRE's whitelist. A project has at least one catalog, in either list, and the ids are unique.

The TRE's domains are not defined in the file: they are derived from the env's (see
[Names](#names-of-a-projects-resources)), eg `aws-jupyter.cvd-2026.emrys2.click`. Their zones are made by `up`, in the
`create` stage, but have to be delegated from the parent domain before the TRE can get its certificates, which on AWS
`up` issues too, once the zones are delegated. Either `up` does the delegation, or you stop after `create`, delegate,
issue the certificates, and run the rest.

**3. Make the secret of its TRE client.** The TRE's JupyterHub and the gate in front of it sign members in with a client
of the project's own, `cvd-2026-tre`, which has a secret. This writes a random one, readable by its owner only, to
`~/.afon/projects/cvd-2026/keys/tre.secret`:

```shell
afon-cli.sh project-secret cvd-2026 tre
afon-cli.sh manage-project configure cvd-2026
```

`configure` puts the client in the TRE's config, in `secret.properties`: `OAUTH2_CLIENT_ID=cvd-2026-tre` and
`OAUTH2_CLIENT_SECRET`, in place of the env's client, which the env keeps in the same file, and which an id in
`afon.properties` would not override, as `secret.properties` is read after it. `up` gives the identity the same secret
when it makes the client. `configure` stops if the `keys/tre.secret` file is missing. The file is not replaced if it
exists, as the identity and the config were given that secret: `--force` makes another, and `configure` and
`manage-identity create` are run again. See [Client secrets](#client-secrets) for what it is for.

### Automatic delegation

If the parent domain, `emrys2.click`, is a Route 53 hosted zone of your AWS account, as it is here, set this in the
project's file, where the template has `delegation: none`, and `up` runs `dns-delegate --route53` after it makes the
zones, so that it need not stop:

```yaml
dns:
  delegation: route53
```

It writes the `NS` record of each domain into the hosted zone of its closest parent domain, replacing the one that is
there, so it is safe to repeat. It fails, and `up` stops at `create`, if there is no such zone: then delegate by hand. If
a domain of `stacks.tre.domains` is itself a registered domain, it has no parent zone, so `route53` would change its
nameservers at the registrar, and whatever DNS it used before would stop answering for it. Keep `manual` for such a
domain. The default, also for `none` or no value, is `manual`.

**By hand**, stop after `create`, delegate, and run the rest:

```shell
afon-cli.sh manage-project up cvd-2026 --until create
afon-cli.sh manage-project run cvd-2026 dns-delegate aws tre
afon-cli.sh manage-project run cvd-2026 aws-issue-cert --stack aws-full
afon-cli.sh manage-project up cvd-2026 --from register
```

`--until` is the last stage to run; the message at the end gives the `--from` that continues. `dns-delegate` shows the
`NS` records to add to the zone of `emrys2.click`, or with `--route53` writes them there, if that zone is a Route 53
hosted zone of your AWS account (see [DNS Configuration](./dns_config.md)). Check with `afon-cli.sh manage-project run
cvd-2026 aws-dns-zones`. `aws-issue-cert --stack` issues the ACM certificate of each domain that the stack serves, once
the zone is delegated, and `up` runs it itself after `dns-delegate --route53`.

The TRE deploys the stack `aws-full`, with Git in it, and is not rebuilt at night. AWS has no node pool property, so its
nodes are those of the env: mind the vCPU quota of the account in the region, which the TREs and the identity share. It
leaves from its own egress IP, which `up` allows on the database in the `create` stage, as it does for the identity. A
project that names none leaves from the `AWS_EGRESS_IP` of the env's config, which it then shares with the env's own
deployment and with any other project that names none: they cannot be up at the same time.

**4. Check it, configure it and bring it up:**

```shell
afon-cli.sh manage-project validate cvd-2026
afon-cli.sh manage-project plan cvd-2026
afon-cli.sh manage-project configure cvd-2026
afon-cli.sh manage-project up cvd-2026 --dry-run
afon-cli.sh manage-project up cvd-2026
```

`up` runs three stages in the project's home: `create` (`aws-create` for `aws-full`), `register`
(`manage-identity create cvd-2026`: the project's client and group in the central identity, which needs the identity to
be up; see below) and `tre` (`manage-stack setup aws-full`). If one fails, fix the cause and resume with
`manage-project up cvd-2026 --from <stage>`.

The `tre` stage does not run the check that Spark writes a table through the TRE's Polaris
(`aws_spark_talks_to_catalog_aws`), which the setup of a stack outside a project does: the Polaris of a project's TRE
admits only the tokens of the project, with its audience, its `acr` and its project claim, and that check signs in as an
admin user, which has none of them. The members' access is what matters there.

**IMPORTANT:** The project has names of its own, so it can run next to `emrys2`'s own `aws-full`, and next to other
projects made from the same env, see [Names](#names-of-a-projects-resources). It still uses the egress IP of `emrys2`
unless a different egress IP is configured: set `egressIp` in the project's `project.yaml`, which
`manage-project configure` then writes into the project's config.

`manage-identity` calls Keycloak's admin API through a port-forward to its pod, which lives as long as the command.

The `tre` stage configures that Keycloak too, for the TRE's stacks, again through a port-forward. `configure` writes
`KEYCLOAK_KUBECONFIG` into the project's config, naming the kubeconfig of the identity project's cluster, so that the
port-forward goes there and not to the TRE's own cluster, which has no Keycloak. For that, make the identity project
before you `configure` the TRE project, and `up` writes the kubeconfig again before the `tre` stage.

**5. Let it read its Data Providers.** With `cvd-2026` and `lon` both up, its members are made known, and `lon` admits
the TRE and grants it its catalog. Nothing else of `lon`'s config changes for the TRE: it already trusts the central
identity, its own audience (`provider-lon`) and `acr: mfa`, and the TRE's tokens carry that audience because the
project's file names `lon`.

First, the members. `up` has already made the project's clients and group in the central identity, and its members, in
its `register` stage (`manage-identity create`, then `sync-members`), before it deployed the TRE, so nothing of this is
to be done again after `up`. The people who may read are the project's members, and the grants of the third step need
their user ids: `manage-identity` keeps them in `~/.afon/projects/cvd-2026/members.csv`, as the group is, so there is
nothing to copy. The calls are for repeating `register` by hand, in the project's home, if it failed or the members
changed, and `add-member` makes a member if absent, is safe to repeat, and prints their id (see [Members](#members)):

```shell
afon-cli.sh manage-project run cvd-2026 manage-identity add-member alice@tre.example
afon-cli.sh manage-project run cvd-2026 manage-identity create
afon-cli.sh manage-project run cvd-2026 manage-identity sync-members
```

Second, `lon` admits the TRE's egress IP, which it does only for the TREs whose files name it, when it is configured, so
it is configured again once the TRE's file exists. Only the allow-list of its Polaris ingress changes, so it need not be
deployed again: `configure` makes the config, and `kube-polaris-whitelist` sets the allow-list from it, on the cluster
that kubectl points at, until the next deploy, which sets it as the config says, with the TRE's address in it:

```shell
afon-cli.sh manage-project configure lon
afon-cli.sh manage-project run lon azure-update-kube-config azure-polaris
afon-cli.sh manage-project run lon kube-polaris-whitelist azure set
afon-cli.sh manage-project run lon kube-polaris-whitelist azure show
```

`manage-project run` points kubectl at the project's own cluster for the command it runs, whatever `KUBECONFIG` your
shell has: it sets it to the kubeconfig that the project's stack wrote in its `cdktf` dir, `azure-polaris.config` for
`lon`, so there is nothing to export, and a command cannot act on another project's cluster by mistake. If the project
has no kubeconfig yet, `azure-update-kube-config` writes it. `show` prints the CIDRs that the ingress admits: they have
the egress IP of `cvd-2026`. `configure lon` refuses if a TRE that names it leaves from no known address. `afon-cli.sh
manage-project up lon` does the same as part of a full deploy, and is what to run if the stack changes too. This shortcut
has not been tried on `lon`: `show` names the cluster that it acted on.

Third, `lon` grants the project its catalog, in its own Polaris with its own admin credentials, as in [the data
projects](#the-data-projects), for the members in the project's `members.csv`:

```shell
afon-cli.sh manage-project run lon manage-project-grants grant cvd-2026 cvd
```

Without the grant a token of the project reads nothing. Then check that `lon` is serving only its own catalogs, `cvd` and
`dbt`, and, from a notebook of the TRE, that the token it holds, `OIDC_TOKEN`, names the project (the claim `project`),
has `provider-lon` among its audiences and `acr: mfa`. If `lon` refuses it, that decoded token says which of the three is
missing.

## A second TRE project

`dbt-2026` is made the same way as `cvd-2026`, from the same env and with the same identity, and runs at the same time:
each project has its own cluster, storage, domains and state, see [Names](#names-of-a-projects-resources). Each step is
the one above, with `dbt-2026` for `cvd-2026`:

```shell
afon-cli.sh manage-project init dbt-2026 aws --made-from emrys2
afon-cli.sh project-secret dbt-2026 tre
```

Complete `~/.afon/projects/dbt-2026/project.yaml` as for `cvd-2026`. What differs is the id, the audit bucket, the egress
IP, and whatever else is the project's own, such as its Data Provider catalogs: here the `dbt` catalog of each Data
Provider.

**Its own egress IP.** As for `cvd-2026`, reserve an address of its own, and name it in its file:

```shell
afon-cli.sh aws-egress-ip create afon-egress-ip-dbt-2026
afon-cli.sh aws-egress-ip show afon-egress-ip-dbt-2026
```

`up` then allows it on the database, in the `create` stage, as it did for the identity. `validate` and `configure` warn
if two projects of one cloud would leave from the same address, whether they name it or inherit it from the env. Projects
that are not up together may share one.

```yaml
project:
  id: dbt-2026
  cloud: aws
  env: emrys2
  egressIp: <the address of dbt-2026>

identity:
  project: idp
  issuerUrl: https://aws-oidc.idp.emrys2.click/realms/polaris
  acr: mfa

dataProviders:
  named:
    - project: lon
      catalogs: [dbt]
    - project: nw
      catalogs: [dbt]

members:
  - bob@tre.example

stacks:
  create:
    auditBucket: dbt-2026-audit

dns:
  delegation: route53
```

Check it, and see that nothing is shared with `cvd-2026`:

```shell
afon-cli.sh manage-project validate dbt-2026
afon-cli.sh manage-project configure dbt-2026
afon-cli.sh manage-project names cvd-2026
afon-cli.sh manage-project names dbt-2026
```

The two lists differ in the cluster (`full-cvd-2026-cluster`, `full-dbt-2026-cluster`), the buckets
(`afon-<token>-<catalog>`, with another token for each project) and the domains (`aws-jupyter.cvd-2026.emrys2.click`,
`aws-jupyter.dbt-2026.emrys2.click`). `validate` and `configure` refuse a domain that both would have, and
`afon-cli.sh manage-project find <name>` tells which project a bucket belongs to.

Bring it up as for `cvd-2026`, with the delegation of its domains written by `up`:

```shell
afon-cli.sh manage-project up dbt-2026
```

Before you do, mind the quota: the nodes of each TRE are the env's, so the two projects and the identity take three times
what one cluster does of the account's vCPU quota in the region. The projects are brought up and down on their own:
`down dbt-2026` leaves `cvd-2026` and the identity as they are. To put a project in an account of its own, make it from
an env whose config has that account, and the quota is that account's.

## Names of a project's resources

`configure` gives a project names of its own, so that projects, even made from one env or deployed in one cloud account,
never share a resource:

- **The cluster** is `<stack type>-<project id>-cluster`, eg `full-cvd-2026-cluster`, set as `CLUSTER_NAME`. The id makes
  up part of it, so it is checked against the limit of the cloud on `validate`: 40 characters on GKE, 63 on AKS, 100 on
  EKS.
- **What is unique in the whole cloud** is `afonstorage<token>` for an Azure storage account and `afon-...` for the rest,
  where the token is 10 hex characters of a hash of the cloud, the account (the Azure subscription, the AWS account id or
  the GCP project id of the env's config) and the project id. On Azure that is the storage account `afonstorage<token>`
  (21 characters, within the limit of 24) and the key vault `afon-kv-<token>`; on AWS and GCP the buckets of the
  catalogs, `afon-<token>-<catalog>`, and, when the env has them, those of Git and the Polaris logs. The token is the
  same on every `up`, so the audit trail and the other buckets that outlive the stacks stay where they are, and it
  differs for the same project id in another account, as these names are unique across all accounts.
- **The domains** of a TRE are those of the env with the project id as a label after the first: the env's
  `AWS_JUPYTER_DOMAIN_NAME=aws-jupyter.emrys2.click` gives the project `aws-jupyter.cvd-2026.emrys2.click`, and the same
  for the Polaris domain if the env has one. The OIDC domain of a TRE is the identity's: the host of its
  `identity.issuerUrl`. The identity project has its OIDC domain derived in the same way, so that it does not clash with
  a Keycloak that the env serves at its own domain: the env's `AWS_OIDC_DOMAIN_NAME=aws-oidc.emrys2.click` gives
  `aws-oidc.idp.emrys2.click`, which is what the TRE projects name in `identity.issuerUrl`; `stacks.identity.domain` in
  its file wins over the derived one. A Data Provider has its Polaris domain derived too: the env's
  `AZURE_POLARIS_DOMAIN_NAME=azure-polaris.emrys2.click` gives `azure-polaris.lon.emrys2.click`, and `stacks.data.domain`
  wins. An env domain that has no parent domain is left as it is for the identity, and the file has to name one for a
  Data Provider. Each domain has a DNS zone of its own, which `up` makes in its `create` stage (`dns-create-zones`, which
  is safe to repeat), and which the parent zone delegates, as it does the env's own (see [DNS
  Configuration](./dns_config.md)). A domain in `stacks.tre.domains` wins over the derived one, and `validate` and
  `configure` refuse a domain that another project of the same cloud has. The env's domain must have a parent domain:
  `emryspolaris.click` has none, so a project made from it names its own.
- **The catalogs of a Data Provider** are those its file lists, in place of the env's: each is internal, named by its id,
  with storage that is the project's own. On Azure that is a container, named by the id, in the project's storage
  account; on AWS and GCP a bucket named in the whole cloud, `afon-<token>-<id>`. Polaris keeps its database by its
  domain, so a Data Provider's Polaris has a database of its own on the Postgres of the env, and two Data Providers do
  not share one.
- **The Keycloak database** of the identity project is `keycloak-<project>` on the Postgres of the env, eg `keycloak-idp`
  from `jdbc:postgresql://<host>:5432/keycloak-emrys2`, with the parameters of the URL kept. Without it, the project
  would share the realm and the users of a Keycloak that the env deploys itself. The stack creates the database if it is
  absent, and the env's own is left as it is: the project's copy has the derived line in its place in
  `secret.properties`, or, if the env has none, in the block at the end of it.

The account of the env must be set (`AZURE_SUBSCRIPTION`, `AWS_ACCOUNT_ID`, `GCP_PROJECT_ID`), or `configure` stops and
says so.

```shell
afon-cli.sh manage-project names cvd-2026
afon-cli.sh manage-project find afon-5edcf267da-polaris-logs
```

`names` lists them. `find` says which project owns a storage account, key vault or bucket name, from the files under
`~/.afon/projects`.

Where a project deploys, its subscription or account, is the env's. For a TRE to be in an account of its own, make it
from an env whose config has that account; the same account can be shared too, and then the names above keep the projects
apart.

## Running a command in a project

`manage-project run` runs a command of Afon in the home of a project, so that you need not type `--home`. Everything
after the project is the command, as it is, and in the cloud of the project, so that a command that needs one, such as
`cdktf-store`, need not be given `--cloud`, which `afon-cli.sh` would take for itself:

```shell
afon-cli.sh manage-project run idp aws-cluster identity
afon-cli.sh manage-project run cvd-2026 aws-cluster tre
afon-cli.sh manage-project run cvd-2026 cdktf-store list
```

The options that `afon-cli.sh` takes itself (`--env`, `--cloud`, `--home`, `--cdktf`) cannot be given to the command,
which is why the cloud is the project's. In a project's home, `manage-identity` also needs no project id and reaches
Keycloak through the port-forward that `configure` named, so run it there, never in your own env, whose ingress refuses
your machine:

```shell
afon-cli.sh manage-project run cvd-2026 manage-identity add-member <email>
afon-cli.sh manage-project run cvd-2026 manage-identity remove-member <email>
afon-cli.sh manage-project run cvd-2026 manage-identity sync-members
```

`add-member` and `remove-member` change one member at a time. A project that lists its `members` in its
`project.yaml` has them made for it: see [Members](#members).

The state of a project's stacks is stored by `cdktf-store`, as the tag `current`, under the project's id, so apart from
that of any env.

## Members

The members of a TRE project are listed in its `project.yaml`, each as an email, optionally with their first and last
name, which are set only when the member is first made in the central identity:

```yaml
members:
  - alice@tre.example
  - email: bob@tre.example
    firstName: Bob
    lastName: Hope
```

`up` makes the project's group have these members, and no others, in its `register` stage, after the project's clients
and group are made: `manage-identity sync-members`, which you can also run by hand, in the project's home. It
- makes a member who is not in the central identity yet, who is sent an invitation (to verify the email, set a
  password and enrol with MFA), as `add-member` does;
- adds those who are listed and not in the group;
- **removes those who are in the group and not listed**, ending their sessions (a token already issued lasts until it
  expires). The file is the authority: so a member added by hand with `add-member` is removed by the next sync unless it
  is listed.

**The members file.** `manage-identity` writes the project's members, by their user id in the central identity, to
`~/.afon/projects/<project>/members.csv`, a line `<user id>,<email>` for each, as it adds, removes and syncs them:
`sync-members`, which `up` runs, writes the whole group, `add-member` adds one, and `remove-member` takes one out. The
members that the project's `project.yaml` lists are the authority, and the group and this file are made from them by
`sync-members`; so `add-member` of someone who is not listed warns that the next sync takes them out. What needs the ids
reads the file, so none is copied by hand: `manage-project-grants grant <project> <catalog>` of a Data Provider makes the
project's members those in it, and refuses if the file is not the members that `project.yaml` lists, naming those that
are not in it and those that are not listed, so that a grant never gives access to someone the project does not list, nor
leaves out one that it does: `sync-members` puts it right. `--members <file>` names another file, one user id for each
line, or `<user id>,<email>`, which is not checked. The file is rewritten from the group, so it is not edited by hand.

The invitation is an email that the central identity's Keycloak sends, so it needs an SMTP server: see [Setting up
SMTP](#setting-up-smtp), which also says how to send an invitation again.

A project has at least one member: `init` writes `members: []`, which `validate` and `up` refuse until a member is added,
so that a project is never brought up that nobody can sign in to. To change the members, edit the list and run
`afon-cli.sh manage-project up <project> --from register --until register`, or
`manage-project run <project> manage-identity sync-members`. The emails are compared in lower case.

The members are also the users of the TRE's own services: JupyterHub and its remote desktop serve each of them, by their
email, and have them from the TRE's deployment, so a member added later signs in to the central identity at once, but is
served by JupyterHub only after the TRE is deployed again (`manage-project up <project> --from tre`). Members have no
password in the TRE: they sign in with the one they set in the central identity. The users of `users.csv` in the env that
the project is made from are its **admins**, who set it up and run its smoke tests, and who are not served by JupyterHub
unless they are also listed as members, by the same email, which must then be their username in `users.csv`: see
[Users](configuration.md#users).

## Setting up SMTP

The central identity's Keycloak emails each member the invitation to verify their email, set a password and enrol
with MFA, so it needs an SMTP server. This section detals how to set one up with Amazon Simple Email Service (SES), the
service of AWS for sending email, which works well with a domain registered in AWS Route 53, as `emrys2.click` is. Any
other SMTP server works the same way: only the values of the properties differ. With no server, the realm sends nothing:
a member is made all the same, with a warning, but gets no invitation, and a later sync does not send it again.

**1. Verify the domain in SES.** In the AWS console, in the region you send from (the verification, the credentials and
the endpoint all belong to one region, here `eu-west-2`), open Amazon SES, **Identities**, **Create identity**, choose
**Domain** and enter it, eg `emrys2.click`. Leave Easy DKIM on and accept the option to publish its DNS records to Route
53, which adds three CNAME records. Wait a few minutes, until the identity shows **Verified** and its DKIM status
**Successful**. You need none of the optional extras that the console offers (Virtual Deliverability Manager, tenant
management). It is worth setting a **custom MAIL FROM domain**, eg `mail.emrys2.click`, on the **Authentication** tab of
the identity: publish the MX and TXT records it shows, and choose to use the default MAIL FROM domain if they fail. It
improves delivery to inboxes, and changes nothing that you configure here.

**2. Make the SMTP credentials.** In SES, **SMTP settings**, **Create SMTP credentials**, which makes an IAM user: name
it for the env and its use, eg `emrys2-keycloak-smtp`. Keep the **SMTP user name**, which starts with `AKIA`, and the
**SMTP password** that the page shows once. They are not the name of the IAM user nor its secret access key. If you lose
the password, make new credentials, and delete the user of the old ones.

**3. Restrict what that user can send.** The policy that the console attaches lets the user send as any identity of the
account. In IAM, replace it with an inline policy that allows only the sender that Keycloak uses:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ses:SendRawEmail",
      "Resource": "arn:aws:ses:eu-west-2:<account>:identity/*",
      "Condition": {"StringEquals": {"ses:FromAddress": "keycloak@emrys2.click"}}
    },
    {
      "Effect": "Allow",
      "Action": "ses:SendRawEmail",
      "Resource": "arn:aws:ses:eu-west-2:<account>:configuration-set/my-first-configuration-set"
    }
  ]
}
```

Until SES has given the account production access (its **sandbox** sends only to verified addresses and to the addresses
of verified domains), it checks the permission on the identity of each recipient too, and on the account's default
configuration set, if it has one, which is why the first statement covers every identity and the second the configuration
set. A narrower policy fails with `554 Access denied`, naming the resource that it does not allow. After production
access the first statement can name only the identity of the domain, `identity/emrys2.click`, and the second can go.

**4. Set the properties** in the config of the env that the identity project is made from, `afon.properties` and
`secret.properties`:

```properties
KEYCLOAK_SMTP_HOST=email-smtp.eu-west-2.amazonaws.com
KEYCLOAK_SMTP_PORT=587
KEYCLOAK_SMTP_SECURITY=starttls
KEYCLOAK_SMTP_FROM=keycloak@emrys2.click
```

and, in `secret.properties`, `KEYCLOAK_SMTP_USERNAME` (the `AKIA...` user name) and `KEYCLOAK_SMTP_PASSWORD`. The port is
587 if empty, and the security `starttls` (or `ssl`, or `none`) if empty. `KEYCLOAK_SMTP_FROM` must be an address of the
verified domain. Port 25, which clouds block or throttle, is not used.

**5. Send a test email** from this machine, with the config of the active env, to an address you can read:

```shell
afon-cli.sh project-smtp <email> --verbose
```

It says what it connects to and as whom, never the password, and with `--verbose` shows the replies of the server, which
say what is wrong if it fails. `--dry-run` only says what it would send, and `--from <address>` sends from another
address. To test the settings that a project was made with, which are a copy of the env's, run it in the project's home:
`afon-cli.sh manage-project run cvd-2026 project-smtp <email> --verbose`.

| Reply | Cause |
|-------|-------|
| `535 Authentication Credentials Invalid`, or `Login denied` | the user name or password: the `AKIA...` name and the SMTP password of step 2, made in this region |
| `554 Message rejected: Email address is not verified` | the sandbox: SES delivers only to verified identities: verify the recipient, or its domain, or ask SES for production access, see below |
| `554 Access denied ... not authorized to perform 'ses:SendRawEmail' on resource ...` | the policy of step 3 does not allow that resource: add it |
| a timeout | the network does not let this machine, or later the cluster, reach `email-smtp.eu-west-2.amazonaws.com` on port 587 |

**6. Give the settings to the identity.** Configure the identity project again, and run its `identity` stage, whose setup
puts the settings in the realm:

```shell
afon-cli.sh manage-project configure idp
afon-cli.sh manage-project up idp --from identity
```

Then send an invitation from the cluster, to a member who is in the central identity, which also tests the route from
the cluster to the server:

```shell
afon-cli.sh manage-project run cvd-2026 manage-identity invite <email>
```

To send the invitation again, to a member who never got it, let it expire (it is valid for 12 hours, and once), or
lost their second factor, run the same command. It sends the same email, and fails, saying so, if the member is not
in the central identity or the email cannot be sent. If they lost their second factor, remove their authenticator in the
Keycloak admin console first.

**Who SES delivers to, with `aws-ses-identity`.** Until AWS takes the account out of the SES sandbox, every recipient
must be a verified identity of the region, an address, or a domain, which makes every address under it a valid recipient,
so a new member of `rob@example.org` is refused (the `554` above) unless that address, or `example.org`, is verified; the
domain that the mail is sent from does not make the recipients valid. `afon-cli.sh aws-ses-identity` does what is
needed, in the region of `KEYCLOAK_SMTP_HOST` unless `--region` says another:

```shell
afon-cli.sh aws-ses-identity list
afon-cli.sh aws-ses-identity create rob@example.org    # SES emails a link, to click within 24 hours
afon-cli.sh aws-ses-identity create example.org        # prints the three DKIM records to publish in its DNS
afon-cli.sh aws-ses-identity status example.org        # SUCCESS, and sending true, when it can be sent to
afon-cli.sh aws-ses-identity dns example.org           # the records again
afon-cli.sh aws-ses-identity delete rob@example.org    # to have the link sent again, by making it again
afon-cli.sh aws-ses-identity account                   # whether the account is out of the sandbox, and its quota
afon-cli.sh aws-ses-identity production-access \
  --website-url https://example.org --description "<what is sent, to whom>"
```

`production-access` says what it would ask of AWS, and asks it only with `--yes`; AWS reviews the request, usually within
a day, and then mail goes to any address, with no identity to verify. An address that is a group that only forwards has
no inbox to read the link in: its members read it, if the group accepts mail from outside, and a domain is then easier.

A domain identity also publishes DKIM records, which helps an invitation reach the inbox of the member instead of the
spam folder, where one from a sender that the receiver does not trust yet may go; the headers of a received invitation
say what the receiver found (`spf=pass`, `dkim=pass`, `dmarc=pass`).

## Client secrets

- **Who creates the secrets.** The project's deployer creates them: `afon-cli.sh project-secret <project> tre`, and `git`
  for a project with a Git of its own. They are kept in the `keys` dir of the project's own dir, `tre.secret` and
  `git.secret`. `configure` copies the TRE's into the TRE's config, and `manage-identity create` gives both to the
  identity provider when the project is registered, so the two have the same.
- **What it authenticates.** It is per-client: one secret for its TRE client, and another for its Git client if it has
  one. It authenticates the client, the TRE itself, to the identity provider's token endpoint, as the env's
  `OAUTH2_CLIENT_SECRET` does for the client of an env that has no project. It is the TRE proving "I am `<project>-tre`",
  not a member proving who they are. A project has its own, so that its tokens are for its own client, with its own
  audience, project claim and `acr`.
- **Why not a key.** The client was first made to authenticate by a signed assertion with a private key
  (`private_key_jwt`), which Keycloak supports. The tools that sign members in do not: oauth2-proxy, in the version that
  Afon runs (`v7.6.0`), takes a client secret only, and support for a signed client assertion is an open pull request,
  and JupyterHub's `GenericOAuthenticator` takes a `client_id` and a `client_secret` only. A key is to be used again when
  they can present it.
- **PKCE.** The gate in front of JupyterHub asks for PKCE (the S256 method) in a project's TRE. The project's client
  allows it and does not require it: JupyterHub's own sign-in, behind the gate and with the same client, sends none in
  the version that Afon runs, so a client that required it would let only the gate sign in.
- **How members authenticate.** Each member signs in as themselves, in the browser flow, with their own password and a
  second factor. Their email is only their identifier: it is their username in the identity provider, and `sync-members`
  uses it to find or create their account. The first time, they get an invitation email to verify the email, set a
  password and enrol the second factor.

When a member signs in, the identity provider redirects the browser back to the TRE with a code. The TRE's client
exchanges that code for the token, and that exchange is where it presents the secret. The token carries the project claim
only if the member is in the project's group.

The identity project's setup does not register a browser client for a project's TRE: when a TRE's config names a project
claim, the setup of its stacks leaves the client to `manage-identity create`, which makes it with the project's flow,
audiences and claims, as a plain browser client of the same id would overwrite them.

## Taking down the projects

```shell
afon-cli.sh manage-project down dbt-2026
afon-cli.sh manage-project run dbt-2026 manage-identity close
afon-cli.sh manage-project down cvd-2026
afon-cli.sh manage-project run cvd-2026 manage-identity close
afon-cli.sh manage-project down idp
```

The TRE projects are taken down independently, and the shared identity last, as every project uses it. `down` deletes the
stacks, the last first. The reserved IP, the buckets and the projects' config and cdktf dirs are left, as an address may
be on somebody's allow-list.

`manage-identity close` disables the project's TRE client, so that no token is issued for it. Its group and members stay.
To open the project again, run `create`, which makes the client enabled again:

```shell
afon-cli.sh manage-project run cvd-2026 manage-identity create
```

If the shared identity is brought down too, its state is lost with it, so after bringing it up again run `register` for
each project: `manage-project up <project> --from register`.

## What is not done yet

What the page above describes has been run on this deployment (the identity on AWS, `lon` on Azure and `cvd-2026` on
AWS), except what is listed here, which is the way it is meant to work and not yet tried, or is missing. **Not
built** means that the code is not there; **not tried** means that it is, but has not been run on this deployment.

### The Data Providers

- **Not tried: `nw`.** A second Data Provider, made from the same env, with its own egress IP, its audience
  (`provider-nw`) and its catalogs, which `cvd-2026` and `dbt-2026` also read. Only `lon` has been brought up.
- **Not tried: a refusal at `lon`.** Its Polaris requires the issuer of the identity, its own audience and `acr: mfa` of
  every token, and takes the project claim as the one principal role that is active. A spec runs that in a container,
  where a token for another audience, or without the `acr`, is refused; against `lon` a refusal has not been tried.
- **Not built: an admin client for each Data Provider.** `POLARIS_ID` is a property of the env, not derived for each
  project, so the Data Providers made from the same env share one client, which collects the audience of each one that is
  set up, and whoever holds its secret holds the admin access of all of them.
- **Not built: a firewall on the storage of a Data Provider.** The storage account of its catalogs is open to any
  network, and protected by its credentials, so a credential that Polaris vends works from any address.

### The TREs reading the Data Providers

- **Not tried: a member reading a catalog of `lon`.** The grant has been run, and the allow-list admits the TRE; a table
  has still to be released, and read with the session code of the `lon` catalog (`test_lon_cvd`) from a notebook.
- **Partly built: Spark reads the catalogs of the Data Providers, Trino does not.** `configure` writes them into the
  project's catalogs file, as catalogs from the Data Provider source, which `AfonCatalogs.bySource("data-provider")`
  reads. The PySpark session code that `GeneratePySparkKeycloakMain` and `GeneratePySparkContextMain` write has a catalog
  for each, named after its id (`test_lon_cvd`), at the Data Provider's own endpoint, with the notebook's `OIDC_TOKEN`;
  it is only the code, not tried against a Data Provider. Trino has none: each of its catalogs starts with a credential
  of its own, which a Data Provider's Polaris would refuse for lack of its audience and `acr`.
- **Built, not tried: the whitelist of the TRE.** The domains that the TRE may resolve have, for each catalog from a Data
  Provider, the host of its Polaris and its storage: the `blob` endpoint of an Azure storage account, the regional
  endpoint of an AWS bucket, in the region of the TRE's config, as the Data Provider's is not known, and
  `storage.googleapis.com` on GCP. They are derived from the catalogs by `WhitelistConfig`, so nothing is added to
  `whitelist.yaml`, and a config outside a project has none. `network-smoke`, run in the project's home, is what tests
  it, and it has not been run against a project's TRE.
- **Not tried: the read across clouds.** An AWS Spark or Trino reading Azure storage with the credentials that the Data
  Provider's Polaris vends, behind a storage firewall, which there is none of yet.

### What the TRE's tools check

- **Not tried: a member signing in to the TRE.** The project's client, with its secret, is the one that JupyterHub and
  the gate read, and the gate's login is reached. Not shown: a member signing in through the gate and the hub's own login
  behind it, and the token in a notebook, with the project claim, the audience of the Data Provider and `acr: mfa`. A
  TRE's JupyterHub serves the members that it was deployed with, so a member added after is served after `manage-project
  configure <project>`, which copies the project's file, and `manage-project up <project> --from register`.
- **Not built: the TRE's tools do not check the audience, the project claim or the `acr` of a token.** The tokens carry
  them, and the Data Providers check them. It is to do for each tool that takes a token: Trino, Spark and Gitea. A key in
  place of the secret waits for oauth2-proxy and JupyterHub's authenticator to be able to present one, see [Client
  secrets](#client-secrets).

### Members, invitations and the setup of a TRE

- **Not built: the setup of a project's TRE still resets the passwords of the admin users.** It makes the users of the
  env's `users.csv` in the central identity with the passwords of that file, so a password that one of them set through
  an invitation is reset by it. A project's TRE should not touch the users of the shared identity.
- **Not built: `up` does not notice a changed project file.** The TRE reads the copy of `project.yaml` that `configure`
  makes in its config, so a member added to the file is not served by JupyterHub until `configure` is run again.
- **Not built: the invitation has no way back to the TRE.** The page that ends it, "Your account has been updated", has
  no link, as the invitation names no client to go back to: the member opens the TRE's address.
- **Not done: SES delivers only to verified identities.** While the account is in the SES sandbox, each recipient, or its
  domain, has to be verified, and invitations may be filed as spam until the sending domain has DKIM and a DMARC policy:
  see [Setting up SMTP](#setting-up-smtp). Production access removes the first.
- **Not tried: `dbt-2026`, and the smoke tests of a TRE.** A second TRE from the same env, next to `cvd-2026`, and the
  smoke tests of a TRE, which sign in as a user, against a project's Polaris, which admits only the tokens of the
  project.
- **Not built: the restriction of the admin side of Keycloak on AWS.** It is the listener rules of the load balancer
  (`AWS_KEYCLOAK_ALLOWED_CIDRS`), which `kube-keycloak-whitelist` does not change.

### Not on this page

The design, and the options this page leaves out (a project's own Git, its nightly rebuild), are in `multi_project.md` in
the `afon-dev-docs` repository.
