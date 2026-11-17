# Projects

A project is what is deployed together for one purpose, defined by a `project.yaml` that the deployer writes.

We detail here a working end-to-end deployment on Azure, from the env `emrys2`, with three projects:

- `idp`, the project with the role `identity`: the one Keycloak that signs users in for every project;
- `project1`, a project with the role `tre` (the default): a trusted research environment that uses that Keycloak;
- `project2`, a second `tre` project, which runs next to `project1` and shares nothing with it but the identity.

Each project is made from an env, here `emrys2`: its config is a copy of the env's (partially overrideable by
`project.yaml`), so the deployment information (the subscription, resource group, region, domain names, secrets) come
from the env. A project makes its own config, state and home, so running it never interferes with the env you are working
in:

```
~/.afon/projects/<project>/project.yaml    the project definition 
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

The config of `emrys2` already has what the projects use: `AZURE_SUBSCRIPTION`, `AZURE_RESOURCE_GROUP`, `AZURE_REGION`,
`AZURE_OIDC_DOMAIN_NAME` (`azure-oidc.emrys2.click`), `AZURE_JUPYTER_DOMAIN_NAME` (`azure-jupyter.emrys2.click`),
`POLARIS_DB_HOST`, and the Keycloak admin credentials in `secret.properties`. The projects serve their domains under
the project's id, eg the identity project `idp` at `azure-oidc.idp.emrys2.click`: see
[Names](#names-of-a-projects-resources).

## 1. The identity project

**1. Reserve its egress IP.** The identity reaches its database from an address that must not change when it is rebuilt,
so that the database allows it once:

```shell
afon-cli.sh azure-egress-ip create afon-egress-ip-idp
afon-cli.sh azure-egress-ip show afon-egress-ip-idp
```

The script ends with a line about `AZURE_EGRESS_IP` in `afon-azure.properties`: ignore it, as the project carries its own
address.

**2. Make `~/.afon/projects/idp/project.yaml`** from the template of its role, and complete it with the address from step
1:

```shell
afon-cli.sh manage-project init idp azure --role identity --made-from emrys2
```

`--made-from` is the env whose config the project is made from. It is not `--env`, which `afon-cli.sh` takes itself, and
which switches your active env. `init` never overwrites a file, unless given `--force`, which keeps the old one beside it
as `project.yaml.<timestamp>.bak`.

What `init` leaves in `<angle brackets>` is for the deployer to fill in, so `validate` fails until it is replaced. Here
that is the egress IP, which you replace with the address from step 1. The result is:

```yaml
project:
  id: idp
  cloud: azure
  role: identity
  env: emrys2
  egressIp: <the address from step 1>

cluster:
  nodePool:
    vmSize: Standard_D2s_v3
    nodeCount: 2
```

`cluster.nodePool` gives the nodes of the identity's cluster, which runs Keycloak only, so that it does not take the vCPU
quota that the larger nodes of `azure-full` need. It is optional: without it the project uses the nodes of the env, and
both clusters then take the same size.

**3. Check it and configure it:**

```shell
afon-cli.sh manage-project validate idp
afon-cli.sh manage-project configure idp
```

**4. Bring it up**, and check that Keycloak answers:

```shell
afon-cli.sh manage-project up idp --dry-run
afon-cli.sh manage-project up idp
afon-cli.sh azure-keycloak
```

`up` runs two stages in the project's home: `create` (the `azure-create` script for the identity's stack, then
`db-whitelist azure`, which allows the project's egress IP on the Postgres at `POLARIS_DB_HOST`) and `identity`
(`manage-stack setup azure-keycloak`). It stops at the first to fail and says how to resume:
`manage-project up idp --from <stage>`.

## 2. A first TRE project

**1. Make the key pair of its TRE client.** The client authenticates to the identity with a private key, and the identity
is given the public key. This writes the public key to `~/.afon/projects/project1/keys/tre.jwk`, and the private key,
readable by its owner only, to `~/.afon/keys/project1/tre.pem`:

```shell
afon-cli.sh project-key project1 tre
```

Keep the private key in the secret store of the TRE's cloud, and delete that copy: Afon never reads it. See [Key-based
authentication](#key-based-authentication) for what the key is for.

**2. Reserve its egress IP**, as for the identity, so that it leaves from an address of its own, and the env's
`AZURE_EGRESS_IP` stays free for a deployment of the env itself. One address serves the outbound traffic of one cluster,
so projects that run next to each other each need their own:

```shell
afon-cli.sh azure-egress-ip create afon-egress-ip-project1
afon-cli.sh azure-egress-ip show afon-egress-ip-project1
```

**3. Make `~/.afon/projects/project1/project.yaml`** from the template of its role, which is `tre` unless you give
`--role identity`, and complete it, with the address as `egressIp`:

```shell
afon-cli.sh manage-project init project1 azure --made-from emrys2
```

It has the minimum a TRE project needs:

- the issuer of the identity;
- the `acr` (authentication context class reference) value that proves a second factor;
- one Data Provider catalog, here the Polaris of the env itself;
- an audit bucket;
- at least one member;
- the identity project that deploys the identity.

```yaml
project:
  id: project1
  cloud: azure
  env: emrys2
  egressIp: <the address of project1>

identity:
  project: idp
  issuerUrl: https://azure-oidc.idp.emrys2.click/realms/polaris
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

members:
  - alice@tre.example

stacks:
  create:
    auditBucket: project1-audit
```

The TRE's domains are not defined in the file: they are derived from the env's (see
[Names](#names-of-a-projects-resources)), eg `azure-jupyter.project1.emrys2.click`. Their zones are made by `up`, in the
`create` stage, but have to be delegated from the parent domain before the TRE can get its certificates. Either `up` does
it, or you stop after `create`, delegate, and run the rest.

**Automatic delegation.** If the parent domain, `emrys2.click`, is a Route 53 hosted zone of your AWS account, set this
in the project's file, where the template has `delegation: none`, and `up` runs `dns-delegate --route53` after it makes
the zones, so that it need not stop:

```yaml
dns:
  delegation: route53
```

It needs the `aws` CLI and credentials, even if the project is on Azure or GCP. It writes the `NS` record of each domain
into the hosted zone of its closest parent domain, replacing the one that is there, so it is safe to repeat. It fails,
and `up` stops at `create`, if there is no such zone: then delegate by hand. If a domain of `stacks.tre.domains` is
itself a registered domain, it has no parent zone, so `route53` would change its nameservers at the registrar, and
whatever DNS it used before would stop answering for it. Keep `manual` for such a domain. The default, also for `none` or
no value, is `manual`.

**By hand**, stop after `create`, delegate, and run the rest:

```shell
afon-cli.sh manage-project up project1 --until create
afon-cli.sh manage-project run project1 dns-delegate azure tre
afon-cli.sh manage-project up project1 --from register
```

`--until` is the last stage to run; the message at the end gives the `--from` that continues. `dns-delegate` shows the
`NS` records to add to the zone of `emrys2.click`, or with `--route53` writes them there, if that zone is a Route 53
hosted zone of your AWS account (see [DNS Configuration](./dns_config.md)). Check with
`afon-cli.sh manage-project run project1 azure-dns-zones`.

The TRE has a larger `cluster.nodePool`, as `azure-full` does not fit smaller nodes: two `Standard_D4s_v6` are 8 vCPUs of
the `StandardDsv6Family`, so mind the quota of the subscription, which the identity's cluster, and any other, share. On
GCP the keys are `machineType` and `nodeCount`; AWS has no node pool property, so a project there takes the nodes of its
env.

The TRE deploys the stack `azure-full`, with Git in it, and is not rebuilt at night. It leaves from its own egress IP,
which `up` allows on the database in the `create` stage, as it does for the identity. A project that names none leaves
from the `AZURE_EGRESS_IP` of the env's config, which it then shares with the env's own deployment and with any other
project that names none: they cannot be up at the same time.

**4. Check it, configure it and bring it up:**

```shell
afon-cli.sh manage-project validate project1
afon-cli.sh manage-project plan project1
afon-cli.sh manage-project configure project1
afon-cli.sh manage-project up project1 --dry-run
afon-cli.sh manage-project up project1
```

`up` runs three stages in the project's home: `create` (`azure-create` for `azure-full`), `register`
(`manage-identity create project1`: the project's client and group in the central identity, which needs the identity to
be up; see below) and `tre` (`manage-stack setup azure-full`). If one fails, fix the cause and resume with
`manage-project up project1 --from <stage>`.

**IMPORTANT:** The project has names of its own, so it can run next to `emrys2`'s own `azure-full`, and next to other
projects made from the same env, see [Names](#names-of-a-projects-resources). It still uses the egress IP of `emrys2`
unless a different egress IP is configured: set `egressIp` in the project's `project.yaml`, which
`manage-project configure` then writes into the project's config.

`manage-identity` calls Keycloak's admin API through a port-forward to its pod, which lives as long as the command.

The `tre` stage configures that Keycloak too, for the TRE's stacks, again through a port-forward. `configure` writes
`KEYCLOAK_KUBECONFIG` into the project's config, naming the kubeconfig of the identity project's cluster, so that the
port-forward goes there and not to the TRE's own cluster, which has no Keycloak. For that, make the identity project
before you `configure` the TRE project, and `up` writes the kubeconfig again before the `tre` stage.

## 3. A second TRE project

`project2` is made the same way as `project1`, from the same env and with the same identity, and runs at the same time:
each project has its own cluster, storage, domains and state, see [Names](#names-of-a-projects-resources). Each step is
the one above, with `project2` for `project1`:

```shell
afon-cli.sh project-key project2 tre
afon-cli.sh manage-project init project2 azure --made-from emrys2
```

Complete `~/.afon/projects/project2/project.yaml` as for `project1`. What differs is the id, the audit bucket, the egress
IP, and whatever else is the project's own, such as its Data Provider catalogs and its nodes.

**Its own egress IP.** As for `project1`, reserve an address of its own, and name it in its file:

```shell
afon-cli.sh azure-egress-ip create afon-egress-ip-project2
afon-cli.sh azure-egress-ip show afon-egress-ip-project2
```

`up` then allows it on the database, in the `create` stage, as it did for the identity. `validate` and `configure` warn
if two projects of one cloud would leave from the same address, whether they name it or inherit it from the env. Projects
that are not up together may share one.

```yaml
project:
  id: project2
  cloud: azure
  env: emrys2
  egressIp: <the address of project2>

identity:
  project: idp
  issuerUrl: https://azure-oidc.idp.emrys2.click/realms/polaris
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

members:
  - alice@tre.example

stacks:
  create:
    auditBucket: project2-audit
```

Check it, and see that nothing is shared with `project1`:

```shell
afon-cli.sh manage-project validate project2
afon-cli.sh manage-project configure project2
afon-cli.sh manage-project names project1
afon-cli.sh manage-project names project2
```

The two lists differ in the cluster (`full-project1-cluster`, `full-project2-cluster`), the storage account and key vault
(`afonstorage<token>` and `afon-kv-<token>`, with another token for each project) and the domains
(`azure-jupyter.project1.emrys2.click`, `azure-jupyter.project2.emrys2.click`). `validate` and `configure` refuse a
domain that both would have, and `afon-cli.sh manage-project find <name>` tells which project a storage account or a key
vault belongs to.

Bring it up, with the delegation of its domains in the middle, as for `project1`:

```shell
afon-cli.sh manage-project up project2 --until create
afon-cli.sh manage-project run project2 dns-delegate azure tre
afon-cli.sh manage-project up project2 --from register
```
Here, we are assuming that `project2` does **not** configure delegation to `route53`.

Before you do, mind the quota. Each TRE is two `Standard_D4s_v6`, 8 vCPUs of the `StandardDsv6Family`, so the two
projects need 16 of the subscription's quota in the region, next to the identity's nodes (`Standard_D2s_v3`, another
family). The projects are brought up and down on their own: `down project2` leaves `project1` and the identity as they
are. To put a project in an account of its own, make it from an env whose config has that account, and the quota is that
account's.

## Names of a project's resources

`configure` gives a project names of its own, so that projects, even made from one env or deployed in one cloud account,
never share a resource:

- **The cluster** is `<stack type>-<project id>-cluster`, eg `full-project1-cluster`, set as `CLUSTER_NAME`. The id makes
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
  `AZURE_JUPYTER_DOMAIN_NAME=azure-jupyter.emrys2.click` gives the project `azure-jupyter.project1.emrys2.click`, and the
  same for the Polaris domain if the env has one. The OIDC domain is the identity's, and a TRE does not touch it. The
  identity project has its OIDC domain derived in the same way, so that it does not clash with a Keycloak that the env
  serves at its own domain: the env's `AZURE_OIDC_DOMAIN_NAME=azure-oidc.emrys2.click` gives
  `azure-oidc.idp.emrys2.click`, which is what the TRE projects name in `identity.issuerUrl`; `stacks.identity.domain` in
  its file wins over the derived one. An env domain that has no parent domain is left as it is. Each domain has a DNS
  zone of its own, which `up` makes in its `create` stage (`dns-create-zones`, which is safe to repeat), and which
  the parent zone delegates, as it does the env's own (see [DNS Configuration](./dns_config.md)). A domain in
  `stacks.tre.domains` wins over the derived one, and `validate` and `configure` refuse a domain that another project of
  the same cloud has. The env's domain must have a parent domain: `emryspolaris.click` has none, so a project made from
  it names its own.
- **The Keycloak database** of the identity project is `keycloak-<project>` on the Postgres of the env, eg `keycloak-idp`
  from `jdbc:postgresql://<host>:5432/keycloak-emrys2`, with the parameters of the URL kept. Without it, the project
  would share the realm and the users of a Keycloak that the env deploys itself. The stack creates the database if it
  is absent, and the env's own is left as it is: the derived line is the last of the project's `secret.properties`.

The account of the env must be set (`AZURE_SUBSCRIPTION`, `AWS_ACCOUNT_ID`, `GCP_PROJECT_ID`), or `configure` stops and
says so.

```shell
afon-cli.sh manage-project names project1
afon-cli.sh manage-project find afonstorage5edcf267da
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
afon-cli.sh manage-project run idp kube-keycloak-whitelist azure show
afon-cli.sh manage-project run project1 azure-kubectl get pods -A
afon-cli.sh manage-project run project1 cdktf-store list
```

The options that `afon-cli.sh` takes itself (`--env`, `--cloud`, `--home`, `--cdktf`) cannot be given to the command,
which is why the cloud is the project's. In a project's home, `manage-identity` also needs no project id and reaches
Keycloak through the port-forward that `configure` named, so run it there, never in your own env, whose ingress refuses
your machine:

```shell
afon-cli.sh manage-project run project1 manage-identity add-member <email>
afon-cli.sh manage-project run project1 manage-identity remove-member <email>
afon-cli.sh manage-project run project1 manage-identity sync-members
```

`add-member` and `remove-member` change one researcher at a time. A project that lists its `members` in its
`project.yaml` has them made for it: see [Members](#members).

The state of a project's stacks is stored by `cdktf-store`, as the tag `current`, under the project's id, so apart from
that of any env.

## Members

The researchers of a TRE project are listed in its `project.yaml`, each as an email, optionally with their first and last
name, which are set only when the researcher is first made in the central identity:

```yaml
members:
  - alice@tre.example
  - email: bob@tre.example
    firstName: Bob
    lastName: Hope
```

`up` makes the project's group have these members, and no others, in its `register` stage, after the project's clients
and group are made: `manage-identity sync-members`, which you can also run by hand, in the project's home. It
- makes a researcher who is not in the central identity yet, who is sent an invitation (to verify the email, set a
  password and enrol with MFA), as `add-member` does;
- adds those who are listed and not in the group;
- **removes those who are in the group and not listed**, ending their sessions (a token already issued lasts until it
  expires). The file is the authority: so a member added by hand with `add-member` is removed by the next sync unless it
  is listed.

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

The central identity's Keycloak emails each researcher the invitation to verify their email, set a password and enrol
with MFA, so it needs an SMTP server. This section detals how to set one up with Amazon Simple Email Service (SES),
the service of AWS for sending email, which works well with a domain registered in AWS Route 53, as `emrys2.click` is.
Any other SMTP server works the same way: only the values of the properties differ. With no server, the realm sends
nothing: a researcher is made all the same, with a warning, but gets no invitation, and a later sync does not send it
again.

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
address.

| Reply | Cause |
|-------|-------|
| `535 Authentication Credentials Invalid`, or `Login denied` | the user name or password: the `AKIA...` name and the SMTP password of step 2, made in this region |
| `554 Message rejected: Email address is not verified` | the sandbox: send to an address of the verified domain, or verify the address in SES, or ask SES for production access |
| `554 Access denied ... not authorized to perform 'ses:SendRawEmail' on resource ...` | the policy of step 3 does not allow that resource: add it |
| a timeout | the network does not let this machine, or later the cluster, reach `email-smtp.eu-west-2.amazonaws.com` on port 587 |

**6. Give the settings to the identity.** Configure the identity project again, and run its `identity` stage, whose setup
puts the settings in the realm:

```shell
afon-cli.sh manage-project configure idp
afon-cli.sh manage-project up idp --from identity
```

Then send an invitation from the cluster, to a researcher who is in the central identity, which also tests the route from
the cluster to the server:

```shell
afon-cli.sh manage-project run project1 manage-identity invite <email>
```

To send the invitation again, to a researcher who never got it, let it expire (it is valid for 12 hours, and once), or
lost their second factor, run the same command. It sends the same email, and fails, saying so, if the researcher is not
in the central identity or the email cannot be sent. If they lost their second factor, remove their authenticator in the
Keycloak admin console first.

## Key-based authentication

- **Who creates the keys.** The project's deployer creates the key pair: `afon-cli.sh project-key <project> tre`. The
  public key goes to the identity provider when the project is registered, from the `keys` dir of the project's home. The
  private key stays with the TRE.
- **What it authenticates.** It is per-client: one pair for its TRE client, and another for its Git client if it has one.
  It authenticates the client, the TRE itself, to the identity provider's token endpoint with a signed assertion
  (`private_key_jwt`), instead of a client secret. It is the TRE proving "I am `<project>-tre`", not a member proving who
  they are.
- **How members authenticate.** Each member signs in as themselves, in the browser flow, with their own password and a
  second factor. Their email is only their identifier: it is their username in the identity provider, and `sync-members`
  uses it to find or create their account. The first time, they get an invitation email to verify the email, set a
  password and enrol the second factor.

When a member signs in, the identity provider redirects the browser back to the TRE with a code. The TRE's client
exchanges that code for the token, and that exchange is where it presents the key. The token carries the project claim
only if the member is in the project's group.

## Taking down the projects

```shell
afon-cli.sh manage-project down project2
afon-cli.sh manage-project run project2 manage-identity close
afon-cli.sh manage-project down project1
afon-cli.sh manage-project run project1 manage-identity close
afon-cli.sh manage-project down idp
```

The TRE projects are taken down independently, and the shared identity last, as every project uses it. `down` deletes the
stacks, the last first. The reserved IP, the buckets and the projects' config and cdktf dirs are left, as an address may
be on somebody's allow-list.

`manage-identity close` disables the project's TRE client, so that no token is issued for it. Its group and members stay.
To open the project again, run `create`, which makes the client enabled again:

```shell
afon-cli.sh manage-project run project1 manage-identity create
```

If the shared identity is brought down too, its state is lost with it, so after bringing it up again run `register` for
each project: `manage-project up <project> --from register`.

## What is not done yet

- The TRE's tools do not yet sign in with the project's client, nor check the audience, the project claim or the `acr` of
  a token: the project's client and group exist in Keycloak, and the TRE works as a normal TRE that uses it.
- The Data Provider catalog in `providers` is checked, but not yet read by the engines.

The design, and the options this page leaves out (a project's own Git, its nightly rebuild), are in `multi_project.md` in
the `afon-dev-docs` repository.
