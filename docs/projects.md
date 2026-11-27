# Projects

A **project** is what is deployed together for one purpose: a **trusted research environment (TRE)**, the **Data
Providers** whose catalogs it reads, and the **central identity** that signs its members in. Each of these is a **project
stack**: a stack that is configured in a project, described by a `project.yaml` that the deployer writes, and brought up
with `manage-project-stack`. The project stack of the TRE names the others, its identity and its Data Providers, so it
defines the project, and the TRE project stack id is also the project's.

We detail here a deployment from the env `emrys2`, with the central identity and two research environments on AWS, which
read the data of two Data Providers on Azure:

- `idp`, the project stack with the role `identity`, on AWS: the one Keycloak that signs users in for every project;
- `cvd-2026`, a project stack with the role `tre` (the default), on AWS: a trusted research environment that uses that
  Keycloak, and reads the `cvd` catalog of `lon`;
- `diab-2026`, a second `tre` project stack on AWS, which runs next to `cvd-2026` and shares nothing with it but the
  identity, and reads the `diab` catalog of `nw`;
- `lon` and `nw`, two project stacks with the role `data`, on Azure: the Data Providers, each with its own Polaris and a
  catalog of its own, `cvd` at `lon` and `diab` at `nw`. A Data Provider is another organisation in practice, which
  deploys its own project stack: here we play both.

The project `cvd-2026` is its TRE with `lon` and the identity, and the project `diab-2026` is its TRE with `nw` and the
identity, which the two projects share.

Each catalog is read by one TRE, so what each project reads is isolated by the catalog. Alice is a member of both TREs,
Bob of `cvd-2026` only and Rob of `diab-2026` only: Bob reads `cvd` and not `diab`, Rob `diab` and not `cvd`, and
Alice reads `cvd` when she signs in to `cvd-2026` and `diab` when she signs in to `diab-2026`, as a token carries one
project.

```
AWS     idp ............................ the identity: signs the members in, issues their tokens
         |
         +-- cvd-2026 (aws-full) ....... members alice, bob: reads the catalog cvd of lon
         +-- diab-2026 (aws-full) ...... members alice, rob: reads the catalog diab of nw

Azure   lon (azure-polaris) ............ catalog cvd
        nw  (azure-polaris) ............ catalog diab
```

The identity (`idp`) and the two TREs (`cvd-2026` and `diab-2026`) on AWS, with the Data Providers (`lon` and `nw`) on
Azure, are brought up.  The identity issues the tokens and the members (alice, bob and rob) have their accounts in the
identity, with a second factor, from the invitations that it emailed. A member can sign in to their TRE, reading the
catalog of its Data Provider, while the same member being refused the other TRE's Data Provider.

Each **project stack is made from an env**, here `emrys2`: its config is a copy of the env's (partially overrideable by
`project.yaml`), so the deployment information (the account, region, domain names, secrets) come from the env. What
`configure` derives for the project stack replaces the env's. A new property that the env does not have is
written in a block at the end of the file, under a header saying so. So no file has two lines for a property, and the
env's own files are not changed. A project stack makes its own config, state and home, so running it never interferes
with the env you are working in:

```
~/.afon/projects/<project-stack>/project.yaml    the project stack definition 
~/.afon/projects/<project-stack>/secrets/        the secrets of its clients (for a TRE project stack)
~/.afon/projects/<project-stack>/home/           the Afon home that its commands run in, made by `configure`
~/.afon/config-<project-stack>/                  its config, made by `configure` from the env
~/.afon/cdktf-<project-stack>/                   its stacks, tofu state and kubeconfigs
```

A project stack id is a lower-case DNS label of at most 30 characters, eg `xyz-yyyy-nnnn`.

## Before you start

Log in to AWS, which the identity and the TREs are on, and to Azure, which the Data Providers are on, using
`emrys2` as the active env:

```shell
afon-cli.sh --env emrys2 aws-login
afon-cli.sh --env emrys2 azure-login
```

The config of `emrys2` must have what the project stacks use: `AWS_ACCOUNT_ID`, `AWS_REGION`, `AWS_OIDC_DOMAIN_NAME`
(eg `aws-oidc.emrys2.click`), `AWS_JUPYTER_DOMAIN_NAME` (eg `aws-jupyter.emrys2.click`), `POLARIS_DB_HOST`, and the
Keycloak admin credentials in `secret.properties`. For the Data Providers it also has the Azure properties
`AZURE_SUBSCRIPTION`, `AZURE_RESOURCE_GROUP`, `AZURE_REGION` and `AZURE_POLARIS_DOMAIN_NAME`, and the Polaris
credentials. The parent domain, `emrys2.click`, is a Route 53 hosted zone of the account, so that `up` can delegate the
zones of the project stacks' domains. The project stacks serve their domains under their id, eg the identity project
stack `idp` at `aws-oidc.idp.emrys2.click`: see [Names](#names-of-a-project-stacks-resources).

## The identity project stack

**1. Init the project stack**, making the `project.yaml` file from the template of its role, choosing the name of the
project stack, here `idp`:

```shell
afon-cli.sh manage-project-stack init idp aws --role identity --made-from emrys2
```

`--made-from` is the env whose config the project stack is made from. It is not `--env`, which `afon-cli.sh` takes
itself, and which switches your active env. `init` never overwrites a file, unless given `--force`, which keeps the old
one beside it as `project.yaml.<timestamp>.bak`.

What `init` leaves in `<angle brackets>` is for the deployer to fill in, so `validate` fails until it is replaced. Here
that is the egress IP, which you reserve in the next step.

**2. Reserve its egress IP** for the project stack, and complete the file with it. The identity reaches its database from
an address that must not change when it is rebuilt, so that the database allows it once:

```shell
afon-cli.sh manage-project-stack egress-ip create idp
afon-cli.sh manage-project-stack egress-ip show idp
```

The command runs the `aws-egress-ip` of the project stack's cloud, which is the cloud in its file, and names the address
after the project stack, `afon-egress-ip-idp`, so that project stacks that run next to each other each have one. It
reads nothing of the file but its cloud and the env it is made from, which the file may not have completed yet,
and runs in a home of its own with that env's config, for its region, account and so on, without switching the active
env: if the file still has `<env>`, give it with `--made-from <env>`. It is the step after `init`, as a project stack
cannot be configured until its file has the address. In `~/.afon/projects/idp/project.yaml`, replace the egress IP with
the address that `show` prints, as `egressIp`, and set `dns.delegation` to `route53`: the template has `none`, with
which `up` makes the zone of the project stack's domain but does not delegate it, so the domain does not resolve and
Keycloak cannot get its certificate. The result is:

```yaml
projectStack:
  id: idp
  cloud: aws
  role: identity
  env: emrys2
  egressIp: <the address of idp>

dns:
  delegation: route53
```

AWS has no node pool property, so the nodes of the identity's cluster, as those of the TREs', are those of the env.
`delegation: route53` has `up` write the `NS` records of the project stack's domains into the Route 53 hosted zone of
`emrys2.click`: see [Automatic delegation](#automatic-delegation). It must be set, in every project stack's file.

An address that is not wanted, such as one reserved to try this, is released with
`afon-cli.sh manage-project-stack egress-ip delete idp`. It says what it releases, and leaves an
address that a stack uses, as AWS, Azure and GCP do not release one that is in use: delete that stack first. Anything
that allowed the address, such as a database or a Data
Provider, no longer has it to allow.

**3. Check it, see its stages, and configure it:**

```shell
afon-cli.sh manage-project-stack validate idp
afon-cli.sh manage-project-stack plan idp
afon-cli.sh manage-project-stack configure idp
```

`plan` lists the stages that `up` will run, and the state of each stack.

**4. Bring it up**, and check that Keycloak answers:

```shell
afon-cli.sh manage-project-stack up idp --dry-run
afon-cli.sh manage-project-stack up idp
afon-cli.sh manage-project-stack run idp aws-keycloak
```

`up` runs two stages in the project stack's home: `create` (the `aws-create` script for the identity's stack, then
`db-whitelist aws`, which allows the project stack's egress IP on the Postgres at `POLARIS_DB_HOST`, the zone of its
domain, made by `dns-create-zones` and delegated by `dns-delegate --route53`, and the ACM certificate of the domain,
issued by `aws-issue-cert --stack aws-keycloak`, which needs the zone delegated) and `identity` (`manage-stack setup
aws-keycloak`, which looks the certificate up). The certificate is issued only where `up` delegates, ie with `delegation:
route53`, as it is validated in the delegated zone; with `manual`, issue it yourself, after you delegate. It stops at the
first to fail and says how to resume: `manage-project-stack up idp --from <stage>`, or from a step of its stack, `--from
<stage>:<step>`, see [A first TRE project](#a-first-tre-project).

## The data project stacks

A Data Provider holds data, and lets a project's TRE read it. Each runs one Polaris, whatever the number of projects,
with a catalog for each use of its data. Here `lon` has the catalog `cvd`, which `cvd-2026` reads, and `nw` has the
catalog `diab`, which `diab-2026` reads: no TRE reads the other's. A Data Provider is a project stack with the role
`data`, which deploys the stack `azure-polaris` and creates its catalogs. It is long-lived, as the data it holds: it is
not taken down with a TRE, and not rebuilt.

You are logged in to Azure and the config of `emrys2` has the Azure properties that a Data Provider uses, as [Before you
start](#before-you-start) says. Bring up [the identity project stack](#the-identity-project-stack) first: a Data Provider
trusts it, and its setup reaches its Keycloak.

**1. Init the project stack**, making the `project.yaml` file from the template of its role, choosing the name of the
project stack, here `lon`:

```shell
afon-cli.sh manage-project-stack init lon azure --role data --made-from emrys2
```

**2. Reserve its egress IP**, as for the identity, so that it leaves from an address of its own, named after the
project stack, and complete the `<fields>` of `~/.afon/projects/lon/project.yaml`, with the address as `egressIp` and the
catalogs, and set `dns.delegation` to `route53`, as the template has `none`:

```shell
afon-cli.sh manage-project-stack egress-ip create lon
afon-cli.sh manage-project-stack egress-ip show lon
```

```yaml
projectStack:
  id: lon
  cloud: azure
  role: data
  env: emrys2
  egressIp: <the address of lon>

identity:
  projectStack: idp
  issuerUrl: https://aws-oidc.idp.emrys2.click/realms/polaris
  acr: mfa
  audience: provider-lon

catalogs: [cvd]

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
named by its id, in storage that is the project stack's own. A Data Provider has no clients, group or members in the
central identity: that is for the TRE projects. `cluster.nodePool` is the small nodes of the identity, as a Data Provider
runs Polaris only; AWS has none.

**Its catalogs are its own.** The setup of a stack makes the catalogs that the config lists for every cloud, not just the
stack's, so a Data Provider takes none of the env's: its project stack's cloud lists `cvd`, for `lon`, and the other
clouds list none.

**Who can reach its Polaris.** A Data Provider does not list the TREs that read it: the TREs name it, in their own
`dataProviders.named`. So `configure lon` looks through the project stacks of the Afon home for the TREs that name a
catalog of `lon`, and adds the egress IP of each, the one its file names or else the env's `<CLOUD>_EGRESS_IP`, to
`<CLOUD>_POLARIS_ALLOWED_CIDRS` in the config of `lon`, after what the env already allows there. The deploy, and
`kube-polaris-whitelist`, then restrict the Polaris ingress to those addresses and the cluster's own, and, when
`kube-polaris-whitelist` is told to (`--caller`), the machine it is run from. With no TRE naming `lon`, the property is
left as the env has it, and a Polaris with none is open, guarded by its token checks alone, as any other is. `configure`
refuses if a TRE that names it has no egress IP, neither `projectStack.egressIp` in its file nor the env's
`<CLOUD>_EGRESS_IP`, as it could not be admitted and would be locked out. A TRE that is added or moved is admitted by
configuring and deploying the Data Provider again. The storage account of the catalogs
has no network restriction yet: it is open to any network, and protected by its credentials.

Its Polaris is at the Polaris domain of the env with the project stack's id as a label after the first, eg the env's
`AZURE_POLARIS_DOMAIN_NAME=azure-polaris.emrys2.click` gives `azure-polaris.lon.emrys2.click`, with a database and a
storage account of its own: see [Names](#names-of-a-project-stacks-resources). The env's domain must have a parent
domain, or the file names its own, in `stacks.data.domain`.

**3. Check it, see its stages, configure it and bring it up**, and check that its Polaris is serving:

```shell
afon-cli.sh manage-project-stack validate lon
afon-cli.sh manage-project-stack plan lon
afon-cli.sh manage-project-stack configure lon
afon-cli.sh manage-project-stack up lon --dry-run
afon-cli.sh manage-project-stack up lon
afon-cli.sh manage-project-stack run lon azure-polaris
```

`up` runs two stages in the project stack's home: `create` (`azure-create` for the stack `azure-polaris`, `db-whitelist
azure`, which allows the project stack's egress IP on the Postgres, and the zone of its domain, delegated by `route53`)
and `data` (`manage-stack setup azure-polaris`, which also creates the catalogs, after `aws-update-kube-config
aws-keycloak` has written the kubeconfig of `idp`'s cluster, in its own home). `down lon` deletes the stack and leaves
its storage, with the data of its catalogs.

`azure-polaris` checks each layer, run in the Data Provider's home so that it uses its config: the Azure login, the AKS
cluster and its nodes, the Polaris pod, the ingress's external IP, the TLS secret, and then real HTTPS requests to its
domain, anonymous and as the Polaris admin (`POLARIS_ID`). The last one gets a token from the central identity and calls
this Polaris with it, so it shows that the audience and `acr` that the Data Provider requires are in that client's token.

**Repeat steps 1 to 3 for `nw`**, the second Data Provider: the same commands, with `nw` for `lon`, its own egress IP
(`afon-egress-ip-nw`), its own audience (`provider-nw`) and its own catalog, which here is `diab`:

```shell
afon-cli.sh manage-project-stack init nw azure --role data --made-from emrys2
afon-cli.sh manage-project-stack egress-ip create nw
afon-cli.sh manage-project-stack egress-ip show nw
# complete ~/.afon/projects/nw/project.yaml as for lon, with that address, then:
afon-cli.sh manage-project-stack validate nw
afon-cli.sh manage-project-stack plan nw
afon-cli.sh manage-project-stack configure nw
afon-cli.sh manage-project-stack up nw --dry-run
afon-cli.sh manage-project-stack up nw
afon-cli.sh manage-project-stack run nw azure-polaris
```

A Data Provider holds its own catalogs and no others: `configure` empties the catalog ids of the clouds other than the
project stack's, which the env lists for its own stacks. Polaris keeps its catalogs in its database, which survives a
rebuild, so catalogs that an earlier deployment made stay: delete one through Polaris's own API (its catalog roles other
than `catalog_admin` first, then `DELETE /api/management/v1/catalogs/<name>`), as `manage-polaris-catalogs delete` looks
a catalog up in the config, which no longer lists it.

**4. Grant a project its catalog** (once its TRE exists, as it needs its members, and for `nw` the same as for `lon`,
with `diab-2026` and its catalog `diab`).

A project reads a Data Provider's data only if three parties each do their part, and no party can do another's. The Data
Provider decides what a project reads; the TRE's side decides who its members are; the central identity decides who may
sign in. This is what each controls:

| | Controls | Does not control |
|---|---|---|
| **The Data Provider** (the operator of its project stack, with its Polaris admin credentials, `POLARIS_ID` and `POLARIS_SECRET` in its config) | Its Polaris and its catalogs. The namespaces of a catalog: `staging`, where it makes and checks its tables, which no project reads, and `released`, which projects read. Which tables are released. Which projects read which catalog: the project's principal role and catalog role. Which members of a project have a principal, and so may read. Which addresses its ingress admits, `<CLOUD>_POLARIS_ALLOWED_CIDRS`. | Who the members of a project are, how they sign in, and what a TRE does with what it reads. |
| **The TRE project** (its operator) | Its own `project.yaml`: its members, which is the authority for who is in the project, its egress IP, and the Data Providers and catalogs that it names in `dataProviders`, which is a request to read, not a grant. Its client and its secret. | What a Data Provider lets it read. It cannot make a role, a principal or a grant in a Data Provider's Polaris, and adding a member to its group gives no access to a Data Provider's data. |
| **The central identity** (the operator of the identity project stack) | Who exists, their second factor, and the group of each project. It issues a project's token only to a member of the project's group, after the second factor, and puts in it the project, the audiences of the Data Providers that the project names, and `acr`. | Whether a Data Provider admits the holder of a token. A token alone reads nothing. |

So, for a member to read a table, all four of these hold, and the TRE's side can make only the first:

1. **The member has a token** for the project: they are in the project's `members`, so in its group in the identity, and
   have signed in with their second factor. (The TRE's side, and the identity.)
2. **The Data Provider's ingress admits the TRE's egress IP.** (The Data Provider; derived from the TRE's file, see step
   5.)
3. **The Data Provider has a principal for the member, with the project's role,** which is what `grant` makes below. A
   member who is in the group and has no principal there reads nothing. (The Data Provider.)
4. **The table is in `released`.** (The Data Provider, with `release`.)

The Data Provider lets a project in, with its own grants, in its own Polaris and with its own admin credentials. The
TRE's side never does. Running the grant of the Data Provider's project stack gives the project `cvd-2026` access to the
`cvd` catalog of `lon`. Its members are those of the project's `members.csv`, which `manage-identity` keeps up to date as
it adds and removes members (see [Members](#members)), so no user id is copied by hand:

```shell
afon-cli.sh manage-project-stack run lon manage-data grant cvd-2026 cvd
```

The command reaches the Data Provider's Polaris through a port-forward to its pod, as the setup steps do
(`POLARIS_ACCESS`, `port-forward` by default), so this machine need not be among the addresses that its ingress admits;
with `POLARIS_ACCESS=https` it calls Polaris at its public domain name, and the machine must be admitted.

It makes, for the project, a principal role `cvd-2026Project` and a catalog role `cvd-2026Reader` in the catalog, with
read-only access to the namespace `released` (or the one given with `--namespace`), and nothing else: no write, and not
`staging`, where the Data Provider makes and checks its tables. It makes a principal `afon:<user id>` for each member in
the file, who then has the project's role. The names are the whitelist: a token whose project claim or user claim names
no role or principal that exists reads nothing, as its Polaris checks the tokens.

**How the tables are named, and where the roles apply.** Polaris has catalogs, namespaces and tables, and roles with
privileges on them. It has no notion of a table being released: that is Afon's convention, made of two namespaces in each
catalog of a Data Provider. A table is named `<catalog>.<namespace>.<table>`:
- `cvd.staging.smoke_test` is in `staging`, where the Data Provider makes and checks its tables. No project's role has
  any privilege on it, so no project can list or read it, and it is never visible to a TRE.
- `cvd.released.smoke_test` is in `released`, which is what the projects read. `release cvd smoke_test` is a Polaris
  rename of the table from `staging` to `released` and nothing else: there is no flag on the table, and what makes it
  visible to a project is that the project's role has the privileges of the namespace it is now in.

The privileges are on the **namespace**, not on the catalog. `grant` gives the project's catalog role (`cvd-2026Reader`)
the read-only privileges (list namespaces, read their properties, list tables and views, read their properties, read the
table data) on `released` of the catalog, and none on the catalog itself or on `staging`. The chain from a member to a
table is then: the member's principal `afon:<user id>` has the project's principal role (`cvd-2026Project`), which has
the catalog role in the catalog, which has the privileges on the namespace. This is why a member can list the tables of
`released`, and not the schemas of the catalog, which would take a privilege on the catalog: Polaris answers `403` to
that, by design. In a TRE the catalog has the id `<project-stack>-<catalog>`, eg `lon-cvd`, which is `lon_cvd` in Trino,
so the table above is `lon_cvd.released.smoke_test` there. A namespace other than `released`, given to `grant` with
`--namespace` and to `release` with the same option, takes the place of `released` for that project, and `staging` is
never one.

Whose list of members it grants is the Data Provider's choice:
- **The TRE's list (the default).** The members of `members.csv` of the project, so that the Data Provider admits the
  people whom the TRE's side says are in the project. The file is the authority, as `members` is: a member not in it
  loses the role, and a principal with no role left is deleted. It is checked against the members that the project's
  `project.yaml` lists, and refused if they differ, so that the two cannot drift: the fix is `manage-identity
  sync-members` in the TRE's home.
- **The Data Provider's own list.** `--members <file>`, one user id for each line, or `<user id>,<email>`, for a Data
  Provider that grants only the members that its contract names, which may be fewer than the project's. It is not checked
  against the project stack's file: the Data Provider is the authority for what it grants, and a member of the project
  who is not in it reads nothing from this Data Provider.

It is safe to repeat. The same command changes one member, ends the access, or moves a table that was checked from
`staging` to `released`, which is the Data Provider's step, never the TRE's:

```shell
afon-cli.sh manage-project-stack run lon manage-data add-member cvd-2026 <user id>
afon-cli.sh manage-project-stack run lon manage-data remove-member cvd-2026 <user id>
afon-cli.sh manage-project-stack run lon manage-data release cvd patients
afon-cli.sh manage-project-stack run lon manage-data revoke cvd-2026 cvd
afon-cli.sh manage-project-stack run lon manage-data show [cvd-2026]
```

`show` reads and changes nothing. It prints what each catalog of the Data Provider holds, its namespaces and their
tables, all sorted by name so that it reads the same each time, and then, for each project that has a principal role
there (or the one named), the members that have it, by user id with their email where the project's `members.csv` has
one, and its access in each catalog:

```
Catalogs:
  cvd: released [smoke_test]; staging [smoke_test_unreleased]
Project cvd-2026 (principal role cvd-2026Project)
  members (2): afon:8f2d9c6b-... (alice@emrys.health), afon:a30ed4c7-... (bob@emrys.health)
  cvd: catalog role cvd-2026Reader, read-only on released
```

Use it after `grant`, `revoke` or `release` to see what the Data Provider now allows, and to find a project that it still
grants that its TRE no longer names. A project that has been revoked, or never granted, is not listed.

In these commands `<user id>` is the **Keycloak user id** that the central identity gave the member, a UUID, not their
email: the principal is `afon:<user id>`, the same id that is the `polaris_principal` claim of their token, and an email
is refused. To find it from the email, in the TRE project stack's home, without changing anything:

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity user-id alice@tre.example
```

It prints the id alone, one line, and fails, saying so, if the member is not in the central identity (`add-member` makes
them, and sends the invitation, which `user-id` does not). The id is also in the project's `members.csv`, one `<user
id>,<email>` line for each member, and `add-member` prints it too, but also makes the member if absent. `grant` reads
`members.csv` itself, so for the whole project no id is typed.

Taking access away is also the Data Provider's: `revoke` or `remove-member` deletes the role or the principal, and by the
design its Polaris then refuses that member's tokens, whatever the identity still lets them sign in with. 
Removing a member from the TRE's `members` does not remove their principal at a Data Provider; the Data
Provider does that, with `remove-member`, or with a `grant` from a list without them. Credentials for the storage that
Polaris has already vended work until they expire, which is up to an hour in the design, and a token that the identity
has issued is valid until it expires.

A project whose catalog is its own, as `cvd` is `cvd-2026`'s and `diab` is `diab-2026`'s, is isolated by the catalog:
the members of another project have no role in it, and their tokens do not carry its audience. One catalog shared by
several projects is only isolated by the namespace of each, `--namespace`, so give each its own, or do not share one.

A TRE names the catalogs of a Data Provider in its `dataProviders`, as [below](#a-first-tre-project): it takes the
**catalog endpoint** (`https://azure-polaris.lon.emrys2.click/api/catalog`), the **audience** that its tokens carry for
it (`provider-lon`) and the **storage account** of the catalog from the Data Provider's project stack. Naming a Data
Provider asks to read it; the Data Provider decides, with the grants above. Who the project's members are is the TRE's,
from its `members.csv` (see below, and [What is not done yet](#what-is-not-done-yet) for what has been run).

## A first TRE project

**1. Init the project stack**, making the `project.yaml` file from the template of its role, which is by default `tre`
unless you give a different `--role` flag, choosing the name of the project stack, here `cvd-2026`:

```shell
afon-cli.sh manage-project-stack init cvd-2026 aws --made-from emrys2
```

**2. Reserve its egress IP** for the project stack, as for the identity, so that it leaves from an address of its own,
and the env's `AWS_EGRESS_IP` stays free for a deployment of the env itself. One address serves the outbound traffic of
one cluster, so project stacks that run next to each other each need their own. A Data Provider allows this address too,
to let the TRE read its data:

```shell
afon-cli.sh manage-project-stack egress-ip create cvd-2026
afon-cli.sh manage-project-stack egress-ip show cvd-2026
```

Then complete the `<fields>` of the file, with the address as `egressIp`, and set `dns.delegation` to `route53`, as the
template has `none`, which leaves the domains of the project stack undelegated. The file has the minimum a TRE project
stack needs:

- the issuer of the identity;
- the `acr` (authentication context class reference) value that proves a second factor;
- the Data Provider catalogs, one entry each: here the `cvd` catalog of `lon`;
- an audit bucket;
- at least one member;
- the identity project stack that deploys the identity.

```yaml
projectStack:
  id: cvd-2026
  cloud: aws
  env: emrys2
  egressIp: <the address of cvd-2026>

identity:
  projectStack: idp
  issuerUrl: https://aws-oidc.idp.emrys2.click/realms/polaris
  acr: mfa

dataProviders:
  named:
    - projectStack: lon
      catalogs: [cvd]

members:
  - alice@tre.example
  - bob@tre.example

stacks:
  create:
    auditBucket: cvd-2026-audit

dns:
  delegation: route53
```

Each entry of `named` names a Data Provider's project stack, `lon` or `nw`, and its catalogs, whose files and config say
the rest: the endpoint of its Polaris (`https://azure-polaris.lon.emrys2.click/api/catalog`), the audience that the
tokens for it carry (`provider-lon`, that of its identity), the storage account of each catalog, which the TRE's
whitelist allows, and the cloud of that storage, which is not the project stack's here. `validate`, `configure` and `up`
read them from the Data Provider's project stack, which has to be made, with the config of its env there, though not yet
up; they refuse a project stack or catalog that is not there, and a Data Provider that trusts another identity than the
one the TRE uses. Each catalog is an entry in the TRE, with the id `<project-stack>-<catalog>`: a TRE that reads two
catalogs of a Data Provider, which `lon` has not, names them together, `- {projectStack: lon, catalogs: [cvd, dbt]}`,
and has `lon-cvd` and `lon-dbt`. `configure` writes the catalogs in full, as `defined`, into the copy of the file in the
project stack's config, as the record of what was deployed. It also writes them into the project stack's config as
catalogs, like any other, in the `catalogs-<cloud>.properties` of the cloud of their storage: each id, which is that of
the entry with its hyphens as underscores, eg `lon_cvd`, is in `<CLOUD>_CATALOG_IDS` with the env's, and has
`<CLOUD>_CATALOG_NAME_<id>`, `_CATALOG_URI_<id>`, `_CATALOG_AUDIENCE_<id>` and `_BUCKET_NAME_<id>`, and
`<CLOUD>_CATALOG_SOURCE_<id>=data-provider`, which says it is served from the Data Provider's Polaris and not from the
stack's own, as the default, `stack`, says of every other. `AfonCatalogs.bySource("data-provider")` returns them, and
`AfonCatalogs.all()` is what it was, the stack's own, which is what its Polaris makes and its engines query. The scripts
that make and delete the buckets of the stack's catalogs, `<cloud>-create` and `<cloud>-destroy`, skip a catalog of the
Data Provider source, as does `config-validate` when it checks for catalogs that share a bucket, so that none of them
makes or deletes the storage of a Data Provider. A config outside a project has none.

A Data Provider that Afon did not deploy has no project stack to name: its catalog is then given in full, in `defined`,
as `id`, `catalogUri`, `catalogName`, `audience`, `bucket` and `cloud`. The `audience` is one for each Data Provider in
the first pass, so that the catalogs of one Data Provider share its audience, eg `provider-lon`; `bucket` is the storage
account of the catalog, for the TRE's whitelist. A TRE project stack has at least one catalog, in either list, and the
ids are unique.

The TRE's domains are not defined in the file: they are derived from the env's (see
[Names](#names-of-a-project-stacks-resources)), eg `aws-jupyter.cvd-2026.emrys2.click`. Their zones are made by `up`, in
the `create` stage, but have to be delegated from the parent domain before the TRE can get its certificates, which on AWS
`up` issues too, once the zones are delegated. Either `up` does the delegation, or you stop after `create`, delegate,
issue the certificates, and run the rest.

**3. Make the secret of its TRE client.** The TRE's JupyterHub and the Pomerium gate in front of it sign members in
with a client of the project's own, `cvd-2026-tre`, which has a secret. This writes a random one, readable by its owner
only, to `~/.afon/projects/cvd-2026/secrets/tre.secret`:

```shell
afon-cli.sh project-secret cvd-2026 tre
afon-cli.sh manage-project-stack configure cvd-2026
```

`configure` puts the client in the TRE's config, in `secret.properties`: `OAUTH2_CLIENT_ID=cvd-2026-tre`,
`OAUTH2_CLIENT_SECRET`, and project-specific `POMERIUM_SHARED_SECRET` and `POMERIUM_COOKIE_SECRET` (reused on rerun),
in place of the env's client, which the env keeps in the same file, and which an id in
`afon.properties` would not override, as `secret.properties` is read after it. `up` gives the identity the same secret
when it makes the client. `configure` stops if the `secrets/tre.secret` file is missing. The file is not replaced if it
exists, as the identity and the config were given that secret: `--force` makes another, and `configure` and
`manage-identity create` are run again.  To migrate an existing project's TRE to the Pomerium gate: rerun `manage-identity create`, so that its client gets the
`auth.<Jupyter domain>` callbacks, and, on AWS, issue the certificate of `auth.<Jupyter domain>` before its next `up` **or `down`**, as both fail when the plan looks it up:
`afon-cli.sh manage-project-stack run <project> aws-issue-cert --stack aws-full`. See [Client secrets](#client-secrets) for what it is for.

### Automatic delegation

If the parent domain, `emrys2.click`, is a Route 53 hosted zone of your AWS account, as it is here, set this in the
project stack's file, where the template has `delegation: none`, and `up` runs `dns-delegate --route53` after it makes
the zones, so that it need not stop:

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
afon-cli.sh manage-project-stack up cvd-2026 --until create
afon-cli.sh manage-project-stack run cvd-2026 dns-delegate aws tre
afon-cli.sh manage-project-stack run cvd-2026 aws-issue-cert --stack aws-full
afon-cli.sh manage-project-stack up cvd-2026 --from register
```

`--until` is the last stage to run; the message at the end gives the `--from` that continues. `dns-delegate` shows the
`NS` records to add to the zone of `emrys2.click`, or with `--route53` writes them there, if that zone is a Route 53
hosted zone of your AWS account (see [DNS Configuration](./dns_config.md)). Check with `afon-cli.sh manage-project-stack
run cvd-2026 aws-dns-zones`. `aws-issue-cert --stack` issues the ACM certificate of each domain that the stack serves,
once the zone is delegated, and `up` runs it itself after `dns-delegate --route53`.

The TRE deploys the stack `aws-full`, with Git in it, and is not rebuilt at night. AWS has no node pool property, so its
nodes are those of the env: mind the vCPU quota of the account in the region, which the TREs and the identity share. It
leaves from its own egress IP, which `up` allows on the database in the `create` stage, as it does for the identity. A
project stack that names none leaves from the `AWS_EGRESS_IP` of the env's config, which it then shares with the env's
own deployment and with any other project stack that names none: they cannot be up at the same time.

**4. Check it, configure it and bring it up:**

```shell
afon-cli.sh manage-project-stack validate cvd-2026
afon-cli.sh manage-project-stack plan cvd-2026
afon-cli.sh manage-project-stack configure cvd-2026
afon-cli.sh manage-project-stack up cvd-2026 --dry-run
afon-cli.sh manage-project-stack up cvd-2026
```

`up` runs three stages in the project stack's home: `create` (`aws-create` for `aws-full`), `register`
(`manage-identity create cvd-2026`: the project's client and group in the central identity, which needs the identity to
be up; see below) and `tre` (`manage-stack setup aws-full`). If one fails, fix the cause and resume with
`manage-project-stack up cvd-2026 --from <stage>`.

`plan` lists the stages, saying for the registration, which has no stack, what it makes and where: the project's client
or clients in the central identity, its group, and its members, which `sync-members` puts in it. The stage is `register`
and not `identity`, which is the stage that deploys the central identity, in the identity project stack:

```
Project stack diab-2026 on aws, with the role tre
Up, in order (down is the reverse):
  1. create (aws-create), state diab-2026/aws-create
  2. register, in the central identity (idp): the client diab-2026-tre, the group diab-2026 and its 2 members
  3. tre (aws-full), state diab-2026/aws-full
```

**Resuming a stage from one of its steps.** A stage that sets up a stack, `identity`, `git`, `tre` or `data`, is made of
the steps of that stack: its deploy, then what it configures. Resuming from the stage runs all of them again, and
**deploying a stack that is running deletes it first**, so a stage that failed in a late step, such as the setup of the
identity's realm after a deploy that succeeded, would make the whole deployment again. Give the step to resume from after
the stage, as `<stage>:<step>`, and the steps before it are not run:

```shell
afon-cli.sh manage-project-stack up cvd-2026 --from tre:configure_oidc_users_for_cloud_aws
```

A stage of a stack that fails says the step that it failed at, and what to resume from, so that it is not typed:

```
Stage tre failed with exit status 1.  Resume with: manage-project-stack up diab-2026 --from tre:configure_oidc_users_for_cloud_aws
```

If the stack does not say which step failed, it is `--from tre`, which runs the whole stack again. A step that is not one
of the stack's is an error that lists the steps.

It is `manage-stack setup aws-full configure_oidc_users_for_cloud_aws` in the project stack's home, for `up` (and
`manage-stack delete <stack> <step>` for `down`), and the stages after it run as they would. The steps of each stack are
listed in [Stacks](stacks.md) and are in the log of the run, `Running step <name>`; `--dry-run` shows the command that
would run. Only the first stage that is run is given the step, and `create` and `register` have none: they are not
stacks.

The smoke test `spark-polaris`, which has Spark write a table through Polaris and read it back, skips a project's TRE:
the Polaris of a project's TRE admits only the tokens of the project, with its audience, its `acr` and its project claim,
and the test signs in as an admin user, which has none of them. The members' access is what matters there.

`up` stops, and says to run `configure`, if the project stack's file has changed since `configure` copied it into the
project stack's config, as the stacks deploy from that copy and a member added to the file, say, would not be served by
JupyterHub; `--from register --until register` alone is made, as the registration reads the file itself, and `--dry-run`
only warns.

**IMPORTANT:** The project stack has names of its own, so it can run next to `emrys2`'s own `aws-full`, and next to other
project stacks made from the same env, see [Names](#names-of-a-project-stacks-resources). It still uses the egress IP of
`emrys2` unless a different egress IP is configured: set `egressIp` in the project stack's `project.yaml`, which
`manage-project-stack configure` then writes into the project stack's config.

`manage-identity` calls Keycloak's admin API through a port-forward to its pod, which lives as long as the command.

The `tre` stage configures that Keycloak too, for the TRE's stacks, again through a port-forward. `configure` writes
`KEYCLOAK_KUBECONFIG` into the project stack's config, naming the kubeconfig of the identity project stack's cluster, so
that the port-forward goes there and not to the TRE's own cluster, which has no Keycloak. For that, make the identity
project stack before you `configure` the TRE project stack, and `up` writes the kubeconfig again before the `tre` stage.

**5. Let it read its Data Providers.** With `cvd-2026` and `lon` both up, its members are made known, and `lon` admits
the TRE and grants it its catalog. Nothing else of `lon`'s config changes for the TRE: it already trusts the central
identity, its own audience (`provider-lon`) and `acr: mfa`, and the TRE's tokens carry that audience because the
project stack's file names `lon`.

First, the members. `up` has already made the project's clients and group in the central identity, and its members, in
its `register` stage (`manage-identity create`, then `sync-members`), before it deployed the TRE, so nothing of this is
to be done again after `up`. The people who may read are the project's members, and the grants of the third step need
their user ids: `manage-identity` keeps them in `~/.afon/projects/cvd-2026/members.csv`, as the group is, so there is
nothing to copy. The calls are for repeating `register` by hand, in the project stack's home, if it failed or the members
changed, and `add-member` makes a member if absent, is safe to repeat, and prints their id (see [Members](#members)):

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity add-member alice@tre.example
afon-cli.sh manage-project-stack run cvd-2026 manage-identity create
afon-cli.sh manage-project-stack run cvd-2026 manage-identity sync-members
```

Second, `lon` admits the TRE's egress IP, which it does only for the TREs whose files name it, when it is configured, so
it is configured again once the TRE's file exists. Only the whitelist of its Polaris ingress changes, so it need not be
deployed again: `configure` makes the config, and `kube-polaris-whitelist` sets the whitelist from it, on the cluster
that kubectl points at, until the next deploy, which sets it as the config says, with the TRE's address in it:

```shell
afon-cli.sh manage-project-stack configure lon
afon-cli.sh manage-project-stack run lon azure-update-kube-config azure-polaris
afon-cli.sh manage-project-stack run lon kube-polaris-whitelist azure set
afon-cli.sh manage-project-stack run lon kube-polaris-whitelist azure show
```

`manage-project-stack run` points kubectl at the project stack's own cluster for the command it runs, whatever
`KUBECONFIG` your shell has: it sets it to the kubeconfig that its stack wrote in its `cdktf` dir, `azure-polaris.config`
for `lon`, so there is nothing to export, and a command cannot act on another project stack's cluster by mistake. If the
project stack has no kubeconfig yet, `azure-update-kube-config` writes it. `show` prints the CIDRs that the ingress
admits: they have the egress IP of `cvd-2026`. `configure lon` refuses if a TRE that names it leaves from no known
address. `afon-cli.sh manage-project-stack up lon` does the same as part of a full deploy, and is what to run if the
stack changes too. This shortcut has not been tried on `lon`: `show` names the cluster that it acted on.

Third, `lon` grants the project its catalog, in its own Polaris with its own admin credentials, as in [the data
project stacks](#the-data-project-stacks), for the members in the project's `members.csv`:

```shell
afon-cli.sh manage-project-stack run lon manage-data grant cvd-2026 cvd
```

Without the grant a token of the project reads nothing. Then check that `lon` is serving only its own catalog, `cvd`, and
test the read from a notebook of the TRE: `scripts/smoke/notebooks/project-spark-reads-data-providers.ipynb`, which is
run in the TRE by the member, checks the token that the notebook holds, `OIDC_TOKEN` (the claim `project`, `provider-lon`
among its audiences, `acr: mfa`), that the notebook pod cannot call the Data Provider's Polaris directly, that Spark
reads a table that `lon` has released, and that it is refused one that it has not. It needs two tables on `lon`, one
released and one left in `staging`, which `lon`'s own smoke test writes (`afon-cli.sh manage-project-stack run lon
spark-polaris-smoke`, see [The Spark write test](smoke_tests.md#the-spark-write-test)); `manage-data
release cvd smoke_test` releases the first. It says `PASS`, `FAIL` or `INCONCLUSIVE` for each step, and the first `FAIL`
says where to look.

**How the notebooks are run.** Two ways, both as a member, as the token they check, `OIDC_TOKEN`, is a member's: nothing else has
one that a project's TRE accepts.

- **From the CLI, in the pod of the smoke member** ([#411](https://github.com/emrys-health/afon/issues/411)). `smoke-notebook`,
  run in the project stack's home, runs `project-spark-reads-data-providers.ipynb`,
  `project-trino-reads-data-providers.ipynb` and `spark-connect-guard.ipynb`, the notebooks of `scripts/smoke/notebooks-project.csv`, as a member's are: it
  signs the first of the `smokeMembers` (see [Members](#members)) in to the TRE's JupyterHub, as a browser does, after
  `manage-identity smoke-credentials`, which has the hub start their pod with their token, and runs the notebooks in that
  pod, so that the token, the cluster's own addresses and the network policy of the pod are a member's.
  `afon-cli.sh manage-project-stack run cvd-2026 smoke-notebook aws`. It first stops the member's server, if it has one, so
  that the sign-in starts a new one, whose token is fresh, and it leaves the pod running after. It fails if a step does and
  prints the notebook's output, which it also writes to the `smoke/notebooks` dir of the project stack's home. The smoke
  member is a member, so each Data Provider must have granted it too: run `manage-data grant` again on it when the smoke
  members change, as it reads the members from `members.csv`.
- **From the CLI, from your machine.** `smoke-notebook --local` runs the same notebooks on your machine, as the smoke member's
  token from Keycloak (`check-oidc --token smoke-member`), through port-forwards to the TRE's Spark Connect and Trino. It is
  quicker and has no pod to start, and it tests the member's reads and refusals, but not the pod: the network policy of the
  notebook pod is not concluded, and the token is not the one that the hub gives. It is for trying things, and the run in the
  pod is the one that mirrors what a member does.
- **Interactively, as a person.** Sign in to the TRE's JupyterHub as a member, copy the notebook into the pod
  (`afon-cli.sh manage-project-stack run cvd-2026 kube-user-cp --user <you> to <path>`, see
  [Jupyter](jupyter.md#copying-notebooks-to-and-from-jupyter-user-pod), or upload it in JupyterLab), run all its cells, and
  read the summary at the end. `kube-user-cp ... from` copies the notebook with its results back.

The same notebook runs as a member of either TRE, with nothing edited. It works out what to expect in three steps:

1. **The member's project is read from their token**, from its `project` claim: `cvd-2026` for a member of `cvd-2026`.
   That is all that it takes from the token (and it checks that the token has the audience, the second factor and some
   time left). It does not read from the token which Data Providers the project may read.
2. **The Data Provider and catalog of a project are in the `PROJECTS` table of the first cell**, written by hand:
   `cvd-2026` reads `cvd` of `lon`, and `diab-2026` reads `diab` of `nw`. Add a project and its Data Provider to the
   table when one is made, and keep it as the `project.yaml` files of the TRE project stacks have it, as nothing checks
   that they agree.
3. **What is expected follows from `TARGET`**, the project whose Data Provider is read, which is the member's own project
   unless you set it:
   - **`TARGET` is the member's own project (the default).** The member must read the Data Provider's released table,
     and be refused its unreleased one: the first step passes if the read works, and the second if it is refused.
   - **`TARGET` is another project**, set in that cell or as `AFON_TARGET` in the notebook's environment, eg `diab-2026`
     in a pod of `cvd-2026`. The member's token is then used against the other project's Data Provider, and every read
     must be refused, so a step passes only if it is: **a `PASS` means refused**, and a `FAIL` means the member read
     what they must not.

The notebooks do not know whether the member should be allowed: the Data Providers decide, and the notebook checks that
they decide as the project says. In the second case, the Spark notebook passes on any refusal, and says in its detail
which layer refused it: the Data Provider's ingress (an HTML `403`, if the TRE's address is not admitted there) or its
Polaris (the member has no role in the catalog). The Trino notebook also passes if the TRE has no catalog for it, as a
TRE has catalogs only for the Data Providers that its file names, which tests the TRE's catalog list and not the Data
Provider.

**Before running either notebook** (`project-spark-reads-data-providers.ipynb` or
`project-trino-reads-data-providers.ipynb`), each Data Provider that the TRE names must have the tables that they read,
which its smoke test writes, and the project must have been let in. Run, for each Data Provider and the TRE that reads it
(`lon` and `cvd-2026`, then `nw` and `diab-2026`, with its catalog `diab`):

```shell
afon-cli.sh manage-project-stack run lon spark-polaris-smoke
afon-cli.sh manage-project-stack run lon manage-data grant cvd-2026 cvd
afon-cli.sh manage-project-stack run lon manage-data release cvd smoke_test
```

The first writes `cvd.staging.smoke_test` and `cvd.staging.smoke_test_unreleased`, with rows, unless they are there
already, in `staging` or, for `smoke_test`, in `released`. The second lets the project in, and may have been run already.
The third releases `smoke_test`, and leaves `smoke_test_unreleased` in `staging`, which the notebooks must be refused.
All three are safe to repeat: the first writes nothing that exists, the second changes nothing that is as it says, and
the third says that the table is released already, and moves nothing, when it is in `released` and not in `staging`. A
table that is in both is a clash, which `release` leaves for Polaris to refuse, as which of the two the projects read is
for the Data Provider to decide. Without the first, the notebooks find no table: they say `INCONCLUSIVE`, or fail to load
it. A Data Provider that is rebuilt (`up`) starts with empty catalogs, so run them again after it. If the Data Provider's
ingress was configured before the TRE's file named it, also admit the TRE, see above.

The whitelist of the TRE, the hosts of the Data Provider's Polaris and storage that Spark may reach, and what the pods
may not, is tested by `network-smoke`, run in the project stack's home: `afon-cli.sh manage-project-stack run cvd-2026
network-smoke`. A project's TRE has no user of its own to spawn a notebook as, so it checks the notebook pod of a member
who is signed in, and skips those checks, saying so, if no one is.

## Operations and tests

A command is one or the other, so that what is run on a real project is never mixed with what only tries it:

| | Commands | What they do |
|---|---|---|
| **Operations**, for a real project | `manage-project-stack` (`init`, `validate`, `plan`, `configure`, `up`, `down`, `nightly`, `run`), `manage-identity`, `manage-data` (`grant`, `revoke`, `add-member`, `remove-member`, `release`, `show`), `project-secret`, `aws-ses-identity`, `kube-polaris-whitelist` | Deploy, configure and operate a project, and let projects in. None writes or reads data. |
| **Tests**, for trying a project | `smoke-test`, `spark-polaris-smoke`, `network-smoke`, `spark-test-importer`, `data-synthetic-ehr`, the notebooks of `scripts/smoke/notebooks` | Write synthetic data (the tables `smoke_test*`, the synthetic EHR) or check behaviour. Never part of an operation, and never run on a real Data Provider's data. |

A test is the same command in a project and outside one, so that a project does not drift from a deployment without
(`data-synthetic-ehr` is not yet, see [What is not done yet](#what-is-not-done-yet)): the Spark write test writes the
stack's own catalog outside a project, and the Data Provider's `staging` namespace in a Data Provider's project stack,
with the one code, signing in as the admin of the Polaris it is run for. What a project adds to the flow is an operation,
`release`.

## How Trino reads a Data Provider

A member queries a Data Provider's catalog from the TRE with Spark, or with Trino, as `lon_cvd` (the catalog's id in
lower case). Either way the member's own token goes to the Data Provider's Polaris, which alone decides what they may
read: its audience, its `acr`, the project claim and the member's principal, see [The data project
stacks](#the-data-project-stacks). Spark declares each catalog in its session with the member's token, so it needs
nothing more. Trino needs a small proxy in the TRE, `trino-catalog-proxy`.

**Why.** Before the first query on a catalog, Trino's Iceberg client calls `GET /v1/config` on the catalog's endpoint. No
member is involved in that call, so it carries the static authentication of the catalog's file, which can only be a
placeholder, as the file holds none of the TRE's credentials. A Data Provider's Polaris takes nothing but a member's
token, so it refuses the call (`401`, and `Cannot obtain metadata` in Trino), and no query on the catalog can start. Only
the calls after it carry the member's token, which Trino sends as the extra credential `token`.

**What the proxy does.** It is an nginx of the TRE's (`nginx-unprivileged`, one pod and a service in Trino's namespace,
made by `TrinoCatalogProxy` when the TRE has Data Provider catalogs), and Trino's catalog for a Data Provider has its
address `http://trino-catalog-proxy:8080/<catalog>`, not the Data Provider's. It handles two cases:

1. `GET /v1/config` it answers itself, with the catalog's prefix, which does not change, so that the call never reaches
   the Data Provider.
2. Every other request it passes to the Data Provider's endpoint, over TLS that it verifies, with the member's
   `Authorization` as Trino sent it.

```
Trino ──(member's token)──► trino-catalog-proxy ──(the same token)──► the Data Provider's Polaris
        GET /v1/config ──►  answered by the proxy, not passed on
```

**What it does not do.** It makes up no identity and holds no credential, and it adds, replaces and checks no token, so
the Data Provider only ever sees the tokens of members, and its rules are as they are for Spark. Nothing is accepted
there that is not a member's.

**What to know.** The hop from Trino to the proxy is plain HTTP, inside the cluster. A pod that can reach the proxy and
holds a member's token can use it as that member would, which holding the token allows anyway; the notebook pods' network
policy does not list the proxy, so they cannot. There is one proxy for all the Data Providers of a TRE, each catalog at a
path of its own, and the TRE's redeploy updates it. A member can list the tables of a Data Provider's `released`
namespace (`SHOW TABLES FROM lon_cvd.released`), and not the catalog's schemas (`SHOW SCHEMAS`), which its read-only role
has no grant to list, so Trino and the Data Provider answer `403` to it, by design. Each Data Provider that the TRE names
must admit the TRE's egress IP at its ingress, as in [A first TRE project](#a-first-tre-project): one brought up before
the TRE's file named it answers every call from the TRE with an nginx `403`, which its Polaris never logs. It is checked
by `scripts/smoke/notebooks/project-trino-reads-data-providers.ipynb`, run by a member, which passed on `cvd-2026` when
it read `lon` and `nw`, once each Data Provider's smoke test has written its tables (see [before running either
notebook](#a-first-tre-project)).

## A second TRE project

`diab-2026` is made the same way as `cvd-2026`, from the same env and with the same identity, and runs at the same time:
each project stack has its own cluster, storage, domains and state, see [Names](#names-of-a-project-stacks-resources).
Each step is the one above, with `diab-2026` for `cvd-2026`:

```shell
afon-cli.sh manage-project-stack init diab-2026 aws --made-from emrys2
afon-cli.sh project-secret diab-2026 tre
```

Complete `~/.afon/projects/diab-2026/project.yaml` as for `cvd-2026`. What differs is the id, the audit bucket, the
egress IP, and whatever else is the project stack's own, such as its Data Provider catalogs: here the `diab` catalog of
`nw`.

**Its own egress IP.** As for `cvd-2026`, reserve an address of its own, and name it in its file:

```shell
afon-cli.sh manage-project-stack egress-ip create diab-2026
afon-cli.sh manage-project-stack egress-ip show diab-2026
```

`up` then allows it on the database, in the `create` stage, as it did for the identity. `validate` and `configure` warn
if two project stacks of one cloud would leave from the same address, whether they name it or inherit it from the env.
Project stacks that are not up together may share one.

```yaml
projectStack:
  id: diab-2026
  cloud: aws
  env: emrys2
  egressIp: <the address of diab-2026>

identity:
  projectStack: idp
  issuerUrl: https://aws-oidc.idp.emrys2.click/realms/polaris
  acr: mfa

dataProviders:
  named:
    - projectStack: nw
      catalogs: [diab]

members:
  - alice@tre.example
  - rob@tre.example

stacks:
  create:
    auditBucket: diab-2026-audit

dns:
  delegation: route53
```

Check it, and see that nothing is shared with `cvd-2026`:

```shell
afon-cli.sh manage-project-stack validate diab-2026
afon-cli.sh manage-project-stack configure diab-2026
afon-cli.sh manage-project-stack names cvd-2026
afon-cli.sh manage-project-stack names diab-2026
```

The two lists differ in the cluster (`full-cvd-2026-cluster`, `full-diab-2026-cluster`), the buckets
(`afon-<token>-<catalog>`, with another token for each project stack) and the domains
(`aws-jupyter.cvd-2026.emrys2.click`, `aws-jupyter.diab-2026.emrys2.click`). `validate` and `configure` refuse a domain
that both would have, and `afon-cli.sh manage-project-stack find <name>` tells which project stack a bucket belongs to.

Bring it up as for `cvd-2026`, with the delegation of its domains written by `up`:

```shell
afon-cli.sh manage-project-stack up diab-2026
```

Before you do, mind the quota: the nodes of each TRE are the env's, so the two TREs and the identity take three times
what one cluster does of the account's vCPU quota in the region. The project stacks are brought up and down on their own:
`down diab-2026` leaves `cvd-2026` and the identity as they are. To put a project stack in an account of its own, make it
from an env whose config has that account, and the quota is that account's.

## End-to-end setup

The whole ecosystem, in the order to run it: the identity, the two Data Providers, the two TREs, and then what lets the
TREs read the Data Providers. Each step has the commands only; the sections above say what each does and what goes in
the files. The example is `idp`, `lon` and `nw`, and the TREs `cvd-2026` (members alice and bob) and `diab-2026`
(members alice and rob), all made from `emrys2`. Replace the ids, the emails and the env with your own.

**The order matters for one reason.** A Data Provider does not list the TREs that read it: the TREs name it, in their own
files, and `configure` of the Data Provider looks through the project stacks of the Afon home for them, to admit their
egress IPs on its Polaris. So write the TREs' files (steps 3 and 4) before you configure and bring up the Data Providers
(step 5), and their first deployment admits both TREs. A TRE added or moved later is admitted by configuring the Data
Provider again and setting its whitelist, see [Adding a TRE later](#adding-a-tre-later).

**0. Before you start.**
- Log in to both clouds, with `emrys2` as the active env, as [Before you start](#before-you-start) says:

  ```shell
  afon-cli.sh --env emrys2 aws-login
  afon-cli.sh --env emrys2 azure-login
  ```

- Check the account's quotas in the region of the env, as the identity and each TRE have a cluster and a VPC of their
  own: the vCPUs (12 for each cluster, of `r6i.xlarge` nodes) and the VPCs (5 by default for each region, so three
  clusters need room next to what the account already has). Raise them first, as a request can take a while to be
  approved, and an `up` that hits one fails halfway:

  ```shell
  aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A --region <region> --query Quota.Value
  aws service-quotas get-service-quota --service-code vpc --quota-code L-F678F1CE --region <region> --query Quota.Value
  aws ec2 describe-vpcs --region <region> --query 'length(Vpcs)'
  ```

- The identity sends the members their invitations by email: have [SMTP](#setting-up-smtp) set up before step 6.

**1. The identity project stack**, [as above](#the-identity-project-stack):

```shell
afon-cli.sh manage-project-stack init idp aws --role identity --made-from emrys2
afon-cli.sh manage-project-stack egress-ip create idp
afon-cli.sh manage-project-stack egress-ip show idp
# complete ~/.afon/projects/idp/project.yaml: egressIp from `show`, and dns.delegation: route53
afon-cli.sh manage-project-stack validate idp
afon-cli.sh manage-project-stack plan idp
afon-cli.sh manage-project-stack configure idp
afon-cli.sh manage-project-stack up idp
afon-cli.sh manage-project-stack run idp aws-keycloak
```

**2. The Data Providers' files**, [as above](#the-data-project-stacks), for `lon` and for `nw`. Make and complete them
now, and configure and bring them up in step 5:

```shell
afon-cli.sh manage-project-stack init lon azure --role data --made-from emrys2
afon-cli.sh manage-project-stack egress-ip create lon
afon-cli.sh manage-project-stack egress-ip show lon
# complete ~/.afon/projects/lon/project.yaml: egressIp, catalogs [cvd],
# audience provider-lon, dns.delegation: route53

afon-cli.sh manage-project-stack init nw azure --role data --made-from emrys2
afon-cli.sh manage-project-stack egress-ip create nw
afon-cli.sh manage-project-stack egress-ip show nw
# complete ~/.afon/projects/nw/project.yaml the same way, with catalogs [diab] and audience provider-nw
```

**3. The TREs' files**, [as above](#a-first-tre-project) and [below](#a-second-tre-project), for `cvd-2026` and for
`diab-2026`. Each has its own egress IP and its own audit bucket, names the catalogs that it reads of `lon` and `nw` in
`dataProviders.named`, and lists its members:

```shell
afon-cli.sh manage-project-stack init cvd-2026 aws --made-from emrys2
afon-cli.sh manage-project-stack egress-ip create cvd-2026
afon-cli.sh manage-project-stack egress-ip show cvd-2026
# complete ~/.afon/projects/cvd-2026/project.yaml: egressIp, dataProviders (lon, catalog cvd),
# members alice and bob, auditBucket cvd-2026-audit, dns.delegation: route53

afon-cli.sh manage-project-stack init diab-2026 aws --made-from emrys2
afon-cli.sh manage-project-stack egress-ip create diab-2026
afon-cli.sh manage-project-stack egress-ip show diab-2026
# complete ~/.afon/projects/diab-2026/project.yaml: egressIp, dataProviders (nw, catalog diab),
# members alice and rob, auditBucket diab-2026-audit, dns.delegation: route53
```

Two TREs that are up together must not share an egress IP: `validate` and `configure` warn if they would.

**4. The secrets of the TREs' clients**, which `configure` of a TRE needs:

```shell
afon-cli.sh project-secret cvd-2026 tre
afon-cli.sh project-secret diab-2026 tre
```

**5. Bring up the Data Providers**, one after the other or together. Their `configure` finds the TRE that names each
from its file, `cvd-2026` for `lon` and `diab-2026` for `nw`, and adds its egress IP to `AZURE_POLARIS_ALLOWED_CIDRS`:

```shell
afon-cli.sh manage-project-stack validate lon
afon-cli.sh manage-project-stack plan lon
afon-cli.sh manage-project-stack configure lon
afon-cli.sh manage-project-stack up lon
afon-cli.sh manage-project-stack run lon azure-polaris
afon-cli.sh manage-project-stack run lon kube-polaris-whitelist azure show

afon-cli.sh manage-project-stack validate nw
afon-cli.sh manage-project-stack plan nw
afon-cli.sh manage-project-stack configure nw
afon-cli.sh manage-project-stack up nw
afon-cli.sh manage-project-stack run nw azure-polaris
afon-cli.sh manage-project-stack run nw kube-polaris-whitelist azure show
```

The `show` of `lon` lists the egress IP of `cvd-2026` and not that of `diab-2026`, which does not name it, and the `show`
of `nw` that of `diab-2026` and not `cvd-2026`, besides the cluster's own addresses. A TRE's address that is missing
means the TRE's file was not valid or did not name the Data Provider when `configure` ran.

**6. Bring up the TREs**, `cvd-2026` and `diab-2026`, which can run at the same time. `up` makes each project's clients
and group in the central identity, and its members, whom the identity invites by email (a member who is in the identity
already is sent no invitation: see below):

```shell
afon-cli.sh manage-project-stack validate cvd-2026
afon-cli.sh manage-project-stack plan cvd-2026
afon-cli.sh manage-project-stack configure cvd-2026
afon-cli.sh manage-project-stack up cvd-2026

afon-cli.sh manage-project-stack validate diab-2026
afon-cli.sh manage-project-stack plan diab-2026
afon-cli.sh manage-project-stack configure diab-2026
afon-cli.sh manage-project-stack up diab-2026
```

A member who is in the central identity already, alice here, is not sent an invitation by `up`, so one who has not set a
password and second factor needs one: `afon-cli.sh manage-project-stack run cvd-2026 manage-identity invite <email>`. A
member signs in with a private window, or after signing out of the identity, if another member's session is open in the
browser, as Keycloak refuses a link of another user while one is.

**7. Let each TRE read its Data Provider.** Each Data Provider grants the TRE that reads it the catalog that it names,
for the members in the TRE's `members.csv`, and releases the tables to read. A TRE is granted no catalog of the other
Data Provider, so its members have no role there:

```shell
afon-cli.sh manage-project-stack run lon spark-polaris-smoke
afon-cli.sh manage-project-stack run lon manage-data grant cvd-2026 cvd
afon-cli.sh manage-project-stack run lon manage-data release cvd smoke_test
afon-cli.sh manage-project-stack run lon manage-data show

afon-cli.sh manage-project-stack run nw spark-polaris-smoke
afon-cli.sh manage-project-stack run nw manage-data grant diab-2026 diab
afon-cli.sh manage-project-stack run nw manage-data release diab smoke_test
afon-cli.sh manage-project-stack run nw manage-data show
```

`spark-polaris-smoke` and `release` are for trying the setup with synthetic tables: a Data Provider releases its real
tables with `release <catalog> <table>`, and decides which. Each catalog is read by one TRE, so its `released` namespace
is not shared. If two TREs were to read one catalog, they would share it, and each would need a namespace of its own with
`--namespace`, see [step 4 of the data project stacks](#the-data-project-stacks).

**8. Check it.** As a member of each TRE, signed in to its JupyterHub, copy
`project-spark-reads-data-providers.ipynb` and `project-trino-reads-data-providers.ipynb` into the pod and run all their
cells, as [How the notebooks are run](#a-first-tre-project) says. Each step says `PASS`, `FAIL` or `INCONCLUSIVE`, and
the first `FAIL` says where to look. They read the released table of the TRE's Data Provider and are refused the
unreleased one. That each member reads only the catalog of their project, bob `cvd` and not `diab`, rob `diab` and not
`cvd`, and alice `cvd` from `cvd-2026` and `diab` from `diab-2026`, is tested by setting `TARGET` in the first cell to
the other project, which makes the notebooks pass only if the member is refused: see [How the notebooks are
run](#a-first-tre-project).

### Adding a TRE later

A TRE made after the Data Providers are up, here `diab-2026` once `lon` and `nw` exist, goes through steps 3, 4 and 6,
and then each Data Provider admits it, which its first deployment did not, as the TRE's file did not exist. For `lon`,
and the same for `nw`:

```shell
afon-cli.sh manage-project-stack configure lon
afon-cli.sh manage-project-stack run lon azure-update-kube-config azure-polaris
afon-cli.sh manage-project-stack run lon kube-polaris-whitelist azure set
afon-cli.sh manage-project-stack run lon kube-polaris-whitelist azure show
```

`configure` finds the TRE from its file, and `kube-polaris-whitelist` sets the ingress from the config that it wrote, on
the cluster of `lon` only. It lasts until the next deploy of `lon`, which sets the same list. Then step 7 for the TRE.

## Names of a project stack's resources

`configure` gives a project stack names of its own, so that project stacks, even made from one env or deployed in one
cloud account, never share a resource:

- **The cluster** is `<stack type>-<project id>-cluster`, eg `full-cvd-2026-cluster`, set as `CLUSTER_NAME`. The id makes
  up part of it, so it is checked against the limit of the cloud on `validate`: 40 characters on GKE, 63 on AKS, 100 on
  EKS.
- **What is unique in the whole cloud** is `afonstorage<token>` for an Azure storage account and `afon-...` for the rest,
  where the token is 10 hex characters of a hash of the cloud, the account (the Azure subscription, the AWS account id or
  the GCP project stack id of the env's config) and the project stack id. On Azure that is the storage account
  `afonstorage<token>` (21 characters, within the limit of 24) and the key vault `afon-kv-<token>`; on AWS and GCP the
  buckets of the catalogs, `afon-<token>-<catalog>`, and, when the env has them, those of Git and the Polaris logs. The
  token is the same on every `up`, so the audit trail and the other buckets that outlive the stacks stay where they are,
  and it differs for the same project stack id in another account, as these names are unique across all accounts.
- **The domains** of a TRE are those of the env with the project stack id as a label after the first: the env's
  `AWS_JUPYTER_DOMAIN_NAME=aws-jupyter.emrys2.click` gives the project stack `aws-jupyter.cvd-2026.emrys2.click`, and the
  same for the Polaris domain if the env has one. The OIDC domain of a TRE is the identity's: the host of its
  `identity.issuerUrl`. The identity project stack has its OIDC domain derived in the same way, so that it does not clash
  with a Keycloak that the env serves at its own domain: the env's `AWS_OIDC_DOMAIN_NAME=aws-oidc.emrys2.click` gives
  `aws-oidc.idp.emrys2.click`, which is what the TRE project stacks name in `identity.issuerUrl`;
  `stacks.identity.domain` in its file wins over the derived one. A Data Provider has its Polaris domain derived too: the
  env's `AZURE_POLARIS_DOMAIN_NAME=azure-polaris.emrys2.click` gives `azure-polaris.lon.emrys2.click`, and
  `stacks.data.domain` wins. An env domain that has no parent domain is left as it is for the identity, and the file has
  to name one for a Data Provider. Each domain has a DNS zone of its own, which `up` makes in its `create` stage
  (`dns-create-zones`, which is safe to repeat), and which the parent zone delegates, as it does the env's own (see [DNS
  Configuration](./dns_config.md)). A domain in `stacks.tre.domains` wins over the derived one, and `validate` and
  `configure` refuse a domain that another project stack of the same cloud has. The env's domain must have a parent
  domain: `emryspolaris.click` has none, so a project stack made from it names its own.
- **The catalogs of a Data Provider** are those its file lists, in place of the env's: each is internal, named by its id,
  with storage that is the project stack's own. On Azure that is a container, named by the id, in the project stack's
  storage account; on AWS and GCP a bucket named in the whole cloud, `afon-<token>-<id>`. Polaris keeps its database by
  its domain, so a Data Provider's Polaris has a database of its own on the Postgres of the env, and two Data Providers
  do not share one.
- **The Keycloak database** of the identity project stack is `keycloak-<project-stack>` on the Postgres of the env, eg
  `keycloak-idp` from `jdbc:postgresql://<host>:5432/keycloak-emrys2`, with the parameters of the URL kept. Without it,
  the project stack would share the realm and the users of a Keycloak that the env deploys itself. The stack creates the
  database if it is absent, and the env's own is left as it is: the project stack's copy has the derived line in its
  place in `secret.properties`, or, if the env has none, in the block at the end of it.

The account of the env must be set (`AZURE_SUBSCRIPTION`, `AWS_ACCOUNT_ID`, `GCP_PROJECT_ID`), or `configure` stops and
says so.

```shell
afon-cli.sh manage-project-stack names cvd-2026
afon-cli.sh manage-project-stack find afon-5edcf267da-polaris-logs
```

`names` lists them. `find` says which project stack owns a storage account, key vault or bucket name, from the files
under `~/.afon/projects`.

The env specifies the subscription or account used by the project stacks.  The same account can be shared by project
stacks, as resource names are kept distinct for each project stack.

## Running a command in a project stack

`manage-project-stack run` runs a command in the home of a project stack, so that you need not specify the home or the
cdktf dir, as these are identified by the project stack id:

```shell
afon-cli.sh manage-project-stack run idp aws-cluster identity
afon-cli.sh manage-project-stack run cvd-2026 aws-cluster tre
afon-cli.sh manage-project-stack run cvd-2026 cdktf-store list
```

The state of a project stack's stacks is stored by `cdktf-store`, as the tag `current`, under the project stack's id, so
apart from that of any env.

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

**The smoke members.** The smoke tests of a project's TRE sign in as a member, as a person does, so that they test the TRE
as a member uses it. The members above are for people, so the members for the tests are listed apart, in `smokeMembers`,
which takes the same entries, is optional, and may not list a member:

```yaml
smokeMembers:
  - smoke@tre.example
```

They are in the project's group too, so `up` and `sync-members` make them, and they sign in as members do. JupyterHub's list of
the users that it admits, and Guacamole's logins, are made when the TRE is deployed, so a smoke member that is added to a
TRE that is up is not admitted by its hub, which answers 403, until `configure` and `up <project-stack> --from tre`. `configure`
writes the first one to `SMOKE_MEMBER` in the project stack's config, and there is none if the list is empty. The
smoke tests need a smoke member's password and the secret of their authenticator, which a person alone knows, so give
them those once the project stack is up:

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity smoke-credentials
```

The command sets a random password for each smoke member, signs them in to the TRE's client, and enrols an authenticator
whose secret it keeps with the password in `secrets/smoke-members.csv` of the project stack, a line `<email>,<password>,<secret>`
for each smoke member, readable by its owner only. It prints neither. It also gives a smoke member that has no names the first and last name that the file lists, else
`Smoke Tests`, because the realm asks a member for them on a page that a login that is scripted cannot fill in. It does nothing for a member who has the file already, and it refuses one who has an
authenticator already, who may be a real person: list another, or `remove-authenticator` first if this one is made for
the tests.

The members are managed via the `manage-identity` command:

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity add-member <email>
afon-cli.sh manage-project-stack run cvd-2026 manage-identity remove-member <email>
afon-cli.sh manage-project-stack run cvd-2026 manage-identity sync-members
afon-cli.sh manage-project-stack run cvd-2026 manage-identity invite <email>
afon-cli.sh manage-project-stack run cvd-2026 manage-identity remove-authenticator <email>
afon-cli.sh manage-project-stack run cvd-2026 manage-identity user-id <email>
```

`user-id` prints the member's Keycloak user id, which `manage-data` takes, and changes nothing.
`add-member` and `remove-member` change one member at a time, `sync-members` synchronises all members. 
`invite` sends a member the invitation again, and `remove-authenticator` removes their authenticators, 
so that they enrol a new one from it: see [Setting up SMTP](#setting-up-smtp).

**The members file.** `manage-identity` writes the project's members, by their user id in the central identity, to
`~/.afon/projects/<project-stack>/members.csv`, a line `<user id>,<email>` for each, as it adds, removes and syncs them.
The members that the project's `project.yaml` lists are the authority. 
The Data Provider's `manage-data grant <project> <catalog>`uses the file to grant access.

The invitation is an email that the central identity's Keycloak sends, so it needs an SMTP server: see [Setting up
SMTP](#setting-up-smtp), which also says how to send an invitation again.

A project has at least one member: `init` writes `members: []`, which `validate` and `up` refuse until a member is added,
so that a project is never brought up that nobody can sign in to. To change the members, edit the list and run
`afon-cli.sh manage-project-stack up <project-stack> --from register --until register`, or
`manage-project-stack run <project-stack> manage-identity sync-members`. The emails are compared in lower case.

Members have no password in the TRE: they sign in with the one they set in the central identity. 
The users of `users.csv` in the env that the project stack is made from are its **admins**, who set it up and
run its smoke tests, and who are not served by JupyterHub unless they are also listed as members, by the same email,
which must then be their username in `users.csv`, or the deploy refuses; to be a member under an email that an admin has,
take the admin out of `users.csv`: see [Users](configuration.md#users).

The setup of a project's TRE leaves the users of the central identity alone: it does not make the users of `users.csv`
there, nor set their passwords again, so a password that someone chose through an invitation is not reset by it.

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

**4. Set the properties** in the config of the env that the identity project stack is made from, `afon.properties` and
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
address. To test the settings that a project was made with, which are a copy of the env's, run it in the project stack's
home: `afon-cli.sh manage-project-stack run cvd-2026 project-smtp <email> --verbose`.

| Reply | Cause |
|-------|-------|
| `535 Authentication Credentials Invalid`, or `Login denied` | the user name or password: the `AKIA...` name and the SMTP password of step 2, made in this region |
| `554 Message rejected: Email address is not verified` | the sandbox: SES delivers only to verified identities: verify the recipient, or its domain, or ask SES for production access, see below |
| `554 Access denied ... not authorized to perform 'ses:SendRawEmail' on resource ...` | the policy of step 3 does not allow that resource: add it |
| a timeout | the network does not let this machine, or later the cluster, reach `email-smtp.eu-west-2.amazonaws.com` on port 587 |

**6. Give the settings to the identity.** Configure the identity project stack again, and run its `identity` stage, whose
setup puts the settings in the realm:

```shell
afon-cli.sh manage-project-stack configure idp
afon-cli.sh manage-project-stack up idp --from identity
```

Then send an invitation from the cluster, to a member who is in the central identity, which also tests the route from
the cluster to the server:

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity invite <email>
```

To send the invitation again, to a member who never got it, let it expire (it is valid for 12 hours, and once), or lost
their second factor, run the same command. It sends the same email, and fails, saying so, if the member is not in the
central identity or the email cannot be sent. The invitation always asks the member to set a password and enrol an
authenticator, so a member who has done both is asked to do both again. A member who lost their second factor, or is to
enrol again under the same device name, has their authenticators removed first, which also ends their sessions; their
password is left as it is, and the next invitation has them enrol a new one:

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity remove-authenticator <email>
afon-cli.sh manage-project-stack run cvd-2026 manage-identity invite <email>
```

**The SES account has to be taken out of the sandbox.** A new SES account is in its **sandbox**, which sends only to
verified identities, so an invitation to a member whose address nobody verified is refused (the `554` above). Before
inviting members, upgrade the account to production access, which lets mail go to any address, with no identity to
verify. `afon-cli.sh aws-ses-identity production-access` asks AWS for it, in the region of `KEYCLOAK_SMTP_HOST` unless
`--region` says another. It says what it would ask and sends nothing until `--yes` is added, so run it first without:

```shell
afon-cli.sh aws-ses-identity production-access \
  --website-url https://<a page that describes the organisation or the service> \
  --description "<what is sent, to whom, from which address>"
afon-cli.sh aws-ses-identity production-access ... --yes    # sends the request
afon-cli.sh aws-ses-identity account                        # production True when AWS has approved it, and the quota
```

AWS reviews the request, usually within a few minutes, and may write to the account's contact address with questions.
What it looks at is the website, and the description of the use: say that it is transactional email only (invitations to
verify an email, set a password and enrol a second factor), from which address (`KEYCLOAK_SMTP_FROM`), to members that an
administrator names one by one, a few messages a day, and that no one is mailed in bulk. Say only what is true: the
account's suppression list for bounces and complaints is on by default, and nothing else need be claimed about
monitoring.

Until it is approved, or for a test, a recipient can be verified instead: an address, whose owner clicks a link that SES
emails them, or a domain, which makes every address under it a valid recipient once the DKIM records that SES prints are
published in its DNS. The domain that the mail is sent from does not make the recipients valid.

```shell
afon-cli.sh aws-ses-identity list
afon-cli.sh aws-ses-identity create rob@example.org    # SES emails a link, to click within 24 hours
afon-cli.sh aws-ses-identity create example.org        # prints the three DKIM records to publish in its DNS
afon-cli.sh aws-ses-identity status example.org        # SUCCESS, and sending true, when it can be sent to
afon-cli.sh aws-ses-identity dns example.org           # the records again
afon-cli.sh aws-ses-identity delete rob@example.org    # to have the link sent again, by making it again
```

An address that is a group that only forwards has no inbox to read the link in: its members read it, if the group accepts
mail from outside, and a domain is then easier.

A domain identity also publishes DKIM records, which helps an invitation reach the inbox of the member instead of the
spam folder, where one from a sender that the receiver does not trust yet may go; the headers of a received invitation
say what the receiver found (`spf=pass`, `dkim=pass`, `dmarc=pass`).

## Client secrets

- **Who creates the secrets.** The project's deployer creates them: `afon-cli.sh project-secret <project> tre`, and `git`
  for a project with a Git of its own. They are kept in the `secrets` dir of the project stack's own dir, `tre.secret`
  and `git.secret`. `configure` copies the TRE's into the TRE's config, and `manage-identity create` gives both to the
  identity provider when the project is registered, so the two have the same.
- **What it authenticates.** It is per-client: one secret for its TRE client, and another for its Git client if it has
  one. It authenticates the client, the TRE itself, to the identity provider's token endpoint, as the env's
  `OAUTH2_CLIENT_SECRET` does for the client of an env that has no project. It is the TRE proving "I am `<project>-tre`",
  not a member proving who they are. A project has its own, so that its tokens are for its own client, with its own
  audience, project claim and `acr`.
- **Why not a key.** The client was first made to authenticate by a signed assertion with a private key
  (`private_key_jwt`), which Keycloak supports. The tools that sign members in do not: Pomerium and JupyterHub's
  `GenericOAuthenticator` take a `client_id` and a `client_secret` only. A key is to be used again when
  they can present it.
- **PKCE.** The project's client allows PKCE and does not require it: neither Pomerium nor JupyterHub's own sign-in
  sends PKCE in the versions that Afon runs, while the client's secret still authenticates the code exchange.
- **How members authenticate.** Each member signs in as themselves, in the browser flow, with their own password and a
  second factor. Their email is only their identifier: it is their username in the identity provider, and `sync-members`
  uses it to find or create their account. The first time, they get an invitation email to verify the email, set a
  password and enrol the second factor.

When a member signs in, the identity provider redirects the browser back to the TRE with a code. The TRE's client
exchanges that code for the token, and that exchange is where it presents the secret. The token carries the project claim
only if the member is in the project's group.

The identity project stack's setup does not register a browser client for a project's TRE: when a TRE's config names a
project claim, the setup of its stacks leaves the client to `manage-identity create`, which makes it with the project's
flow, audiences and claims, as a plain browser client of the same id would overwrite them.

## Taking down the project stacks

```shell
afon-cli.sh manage-project-stack down diab-2026
afon-cli.sh manage-project-stack run diab-2026 manage-identity close
afon-cli.sh manage-project-stack down cvd-2026
afon-cli.sh manage-project-stack run cvd-2026 manage-identity close
afon-cli.sh manage-project-stack down idp
```

The TRE project stacks are taken down independently, and the shared identity last, as every project uses it. `down`
deletes the stacks, the last first. The reserved IP, the buckets and the project stacks' config and cdktf dirs are left,
as an address may be on somebody's whitelist.

`manage-identity close` disables the project's TRE client, so that no token is issued for it. Its group and members stay.
To open the project again, run `create`, which makes the client enabled again:

```shell
afon-cli.sh manage-project-stack run cvd-2026 manage-identity create
```

If the shared identity is brought down too, its state is lost with it, so after bringing it up again run `register` for
each project: `manage-project-stack up <project-stack> --from register`.

## What is not done yet

What the page above describes has been run on this deployment (the identity on AWS, `lon` and `nw` on Azure and
`cvd-2026` and `diab-2026` on AWS), except what is listed here, which is the way it is meant to work and not yet
tried, or is missing. **Not built** means that the code is not there; **not tried** means that it is, but has not been
run on this deployment.

### What has been shown

On 2026-11-23, with the identity, `lon` (catalog `cvd`), `nw` (catalog `diab`), `cvd-2026` (members alice and bob) and
`diab-2026` (members alice and rob) all up, `cvd-2026` and `diab-2026` at the same time, and each reading the Data
Provider that its file names:

- **Two TREs of one env come up at the same time.** `manage-project-stack up cvd-2026` and `up diab-2026`, run in two
  terminals, made two clusters in one account. One of them failed in the setup of the identity's MFA (a call to Keycloak
  got no answer for eight minutes, then `401` as the admin token had expired, which has no clear cause: the two runs were
  not in that step together). It was brought up again. Resuming such a failure from its step, `up <project-stack> --from
  tre:<step>`, does not deploy the stack again: see [A first TRE project](#a-first-tre-project).
- **Each Data Provider admits only the TRE that names it.** `kube-polaris-whitelist azure show` at `lon` lists the egress
  IP of `cvd-2026` and not that of `diab-2026`, and at `nw` that of `diab-2026` and not that of `cvd-2026`.
- **Each Data Provider has only the project roles that it grants.** `lon` has the role of `cvd-2026` in `cvd`, and `nw`
  that of `diab-2026` in `diab`.
- **A member reads their own project's Data Provider.** `project-spark-reads-data-providers.ipynb`, run in the pod of
  the member, passes for bob and alice in `cvd-2026` (reading `cvd` of `lon`) and for rob and alice in `diab-2026`
  (reading `diab` of `nw`): the released table is read, and the unreleased one is refused.
- **A member is refused the other project's Data Provider.** With `TARGET` set to the other project, the same notebook
  passes, meaning that it was refused, for bob in `cvd-2026` against `diab` of `nw`, and for rob in `diab-2026` against
  `cvd` of `lon`. For bob the refusal is the `403` page of `nw`'s ingress, which does not admit `cvd-2026`'s address.
- **Spark and Trino both read, for every member, on the TREs as their files say.** After the TREs were redeployed from
  the files that name one catalog each, `project-spark-reads-data-providers.ipynb` and
  `project-trino-reads-data-providers.ipynb` pass for alice in `cvd-2026` and in `diab-2026`, for bob in `cvd-2026` and
  for rob in `diab-2026`: Trino in `diab-2026` has the catalog `nw_diab`, which it lacked before.
- **A member of the project's group with no principal at the Data Provider reads nothing.** `lon` was granted `cvd-2026`
  for bob alone (`grant cvd-2026 cvd --members <file>`), so alice, in the group, had no principal there: bob still read
  `cvd`, alice was refused it in `cvd-2026`, and alice still read `diab` of `nw` in `diab-2026`, where her principal is a
  different one. `grant` without the file gave her the role back, and she read again. `manage-data show`
  listed who had the role at each step.
- **After the redeploy, a TRE cannot reach the other project's Data Provider at all.** The Spark driver of a TRE may
  connect only to the hosts of the Data Providers that its file names (a Cilium policy, `spark-driver-manifest`), so with
  `TARGET` set to the other project the Spark notebook ends `INCONCLUSIVE`, with `RESTException: Error occurred while
  processing GET request`, a connection that was not made, and not a refusal: bob in `cvd-2026` against `nw`, which also
  admitted `cvd-2026`'s address for the test. The request does not reach the Data Provider's ingress or its Polaris.
- **Polaris itself refuses another project's member.** Three layers stand in front of a Data Provider's Polaris, and the
  run above stopped at the first: the TRE's own whitelist. For the test, that was opened for the one host
  (`azure-polaris.nw.emrys2.click` added to the Cilium policy `spark-driver-manifest` of `cvd-2026`) and `nw`'s ingress
  admitted `cvd-2026`'s address, with both restored afterwards. Bob, in `cvd-2026`, with `TARGET` set to `diab-2026`,
  then reached `nw`'s Polaris, which refused him on the released table and on the unreleased one:
  `NotAuthorizedException: Not authorized`, its own `401`. His token has no `provider-nw` audience, which makes the
  audience check the likeliest, though the message does not say which check of the token refused it: the audience, the
  project claim or the principal.
- **Trino has no catalog of the other project's Data Provider.** The Trino notebook with `TARGET` set to the other
  project passes for alice, bob and rob, meaning that their TRE's Trino has none, so that it cannot read it. That tests
  the TRE's catalog list, and not the Data Provider's refusal, which the Spark notebook covers.
- **A member of two projects reads only the Data Provider of the project they signed in to.** Alice, a member of both,
  was refused `cvd` of `lon` in `diab-2026`: her token there carries the project `diab-2026`, which is the principal
  role that her request asks for, and `lon` grants it none, so access follows the project that she works in and not the
  person. A Data Provider that wanted her to read its catalog from the other TRE would have to grant that project the
  catalog, and admit the TRE.
- **A signed-in member gets a new pod at once** after their pod is deleted (`project-pods delete`), with no new sign-in.

### The Data Providers

- **Not tried: a token for another audience, or without the `acr`, at `lon` or `nw`.** Their Polaris requires the issuer
  of the identity, its own audience and `acr: mfa` of every token, and takes the project claim as the one principal role
  that is active. A spec runs that in a container, where such a token is refused. Against `lon` and `nw` a refusal has
  been seen for a token that the identity did not issue (`401`) and for a table that a project may not read, one left in
  `staging` (`403`), but not for a token of another audience or without the `acr`.
- **Not built: an admin client for each Data Provider, and the admin principals of a TRE's own Polaris
  ([#409](https://github.com/emrys-health/afon/issues/409)).** `POLARIS_ID` is a property of the env, not derived for
  each project, so the Data Providers made from the same env share one client, which collects the audience of each one
  that is set up, and whoever holds its secret holds the admin access of all of them. A TRE's own Polaris still makes its
  admin principals from `users.csv`, which the TRE's setup no longer makes users of in the identity.
- **Not built: `data-synthetic-ehr` in a Data Provider's project stack.** The test that loads synthetic patients is tied
  to the site envs and to the Python env. `spark-test-importer`, which it uses, signs in as the Polaris admin client and
  reaches Polaris by a port-forward in a Data Provider's project stack, as the Spark write test does, but has not been
  tried there. Until then a Data Provider's tables with rows come from `spark-polaris-smoke`, see [Operations and
  tests](#operations-and-tests).
- **Not built: an IP whitelist on the storage of a Data Provider
  ([#412](https://github.com/emrys-health/afon/issues/412)).** The storage account of its catalogs is open to any
  network, and protected only by the credentials that its Polaris vends, so a credential works from any address. The
  firewall is an whitelist of the egress IPs of the TREs that name the Data Provider (the storage firewall rules of an
  Azure storage account, or a source IP condition in the policy of an S3 bucket), as the ingress of its Polaris already
  has; it is not a VPN or a private endpoint. Azure storage IP rules do not apply to traffic from the same Azure region,
  which goes by private addresses, so a TRE in the region of a Data Provider would need another rule, such as a service
  endpoint.

### The TREs reading the Data Providers

- **Not tried: reads behind an IP whitelist on the storage ([#412](https://github.com/emrys-health/afon/issues/412)).**
  Trino on `cvd-2026` (AWS) reads the released tables of `lon` and `nw` (Azure), data files included, with the member's
  token and the credentials that the Data Provider's Polaris vends, and is refused a table that is not released; see [How
  Trino reads a Data Provider](#how-trino-reads-a-data-provider). Spark does the same. Neither has been tried with the
  storage restricted to the TRE's egress IP, which there is none of yet.

### What the TRE's tools check

- **Not built: the TRE's tools do not check the audience, the project claim or the `acr` of a token
  ([#410](https://github.com/emrys-health/afon/issues/410)).** The tokens carry them, and the Data Providers check them,
  so the data is protected, but Trino, the Pomerium gate and JupyterHub, and Gitea and VS Code when they exist, do
  not each require them. A key in place of the secret waits for Pomerium and JupyterHub's authenticator to be able to
  present one, see [Client secrets](#client-secrets).

### Members, invitations and the setup of a TRE

- **Partly built: the smoke tests of a TRE that sign in as a user
  ([#411](https://github.com/emrys-health/afon/issues/411)).** `smoke-notebook` runs the two project notebooks in the pod of
  the smoke member, see [How the notebooks are run](#a-first-tre-project). The other tests that need a token, `smoke-test`'s
  Trino and Spark tests, still sign in as `USER_ID`, who is only an admin in a project's TRE: they are not run as a member.
- **Not built: rotating a client's secret, and stopping a removed member's servers.** `manage-identity rotate-secret`
  does not exist. A secret can be replaced by hand, with `project-secret <project> tre --force`, then `manage-identity
  create` to give the identity the new one, `configure` and a redeploy of the TRE, which has not been tried.
  `manage-identity remove-member` ends the member's sessions in the identity, but a notebook server that they have
  started in the TRE runs until the TRE is rebuilt; `manage-tre-users stop`, which would end it, is not built.
- **Not built: the restriction of the admin side of Keycloak on AWS.** It is the listener rules of the load balancer
  (`AWS_KEYCLOAK_ALLOWED_CIDRS`), which `kube-keycloak-whitelist` does not change.

