# Stacks

## Pre-requisites

### Afon entry point

You can choose one of the Afon [entry points](afon_entry_points.md).

### Setup Local Env

You need to have set up you [local env](setup_local.md).

## Stack deployment

To deploy a stack, using the CLI command, we run [manage-stack](#manage-a-stack) with the `deploy` action, eg:

```shell
afon-cli.sh manage-stack deploy aws-full
afon-cli.sh manage-stack deploy azure-polaris
afon-cli.sh manage-stack deploy gcp-polaris
afon-cli.sh manage-stack deploy gcp-keycloak
```

**NOTE:** To run `manage-stack` you need to be logged in with the corresponding [cloud cli](cloud.md).

**IMPORTANT:** `manage-stack` uses `$AFON_CDKTF` as its `tofu` state dir, so if you deploy or setup a stack (ie
deploy a stack plus configure the Polaris catalogs) you must take care not move your `$AFON_CDKTF` until the teardown of
the stack, other `tofu` will not know the information to destroy the stack.

Of the actions, `deploy` just deploys the stack (waiting for DNS to propagate) and `setup` does the deployment and configures the Polaris catalogs.

### Manage a stack

A single command manages any stack, given an action and a stack id:
```shell
afon-cli.sh manage-stack <action> <stack-id> [<step>] [--replace]  # run without args to list the stack ids and their actions
```
where:
- `<action>` is one of `delete`, `deploy` or `setup`;
- `<stack-id>` is `<cloud>-<name>`, eg `aws-keycloak`, named as `validate-stack` lists the stacks. It can also be
  `<cloud>-<role>`, the stack of the cloud with that role in `stacks.yaml` (`catalog`, `identity` or `tre`), eg `aws-tre`, unless a
  stack is defined under that id, so that it works whatever the ids are. `validate-stack`, `generate-cleanup-script` and the other
  commands taking a stack id accept it too, and so do the scripts (`aws-create`, `aws-cluster`, `tofu-plan`, the kube estimator and so on), which, as their name has the cloud, also take the role alone, eg `aws-create tre`.
  Every defined stack is listed: with the bundled `stacks.yaml`, `aws-full`, `aws-keycloak`, `aws-polaris`, `aws-snowflake`,
  `azure-full`, `azure-keycloak`, `azure-polaris`, `gcp-keycloak` and `gcp-polaris`, and, to delete every stack of a cloud, `<cloud>-all`, eg `aws-all`;
- `<step>`, if given, is the step from which to resume a failed run (see
  [Resume a failed stack deployment](#resume-a-failed-stack-deployment-from-a-specific-step));
- `--replace`, if given, lets `deploy` and `setup` delete the other stacks conflicting with the stack, see
  [stacks side by side](#stacks-side-by-side).  With `afon.sh`, pass it after `--`, eg
  `./afon.sh -a manage-stack -- deploy aws-full --replace`.

For example:
```shell
afon-cli.sh manage-stack setup aws-keycloak
afon-cli.sh manage-stack deploy aws-full wait_on_polaris_and_keycloak_for_cloud_aws
afon-cli.sh manage-stack delete aws-full
```
With `afon.sh`, pass the same args positionally, eg `./afon.sh -a manage-stack setup aws-keycloak`.

To run a scenario on a stack that is set up, ie what its components can be asked to run beyond setting up, use
```shell
afon-cli.sh manage-stack run <stack-id> [<scenario> [<step>]]
```
Without a `<scenario>` it lists those of the stack, and `<step>` resumes a failed run.  For example `aws-snowflake` has
`reading-aws`, `reading-azure`, `reading-gcp` and `writing-azure`, which replace the `deploy-snowflake-*` commands.  Setting
up the stack and running a scenario is the [flow](#run-a-flow-of-stacks) `snowflake-<scenario>`:
```shell
afon-cli.sh manage-stack run snowflake-reading-azure --replace
```

The previous per-stack commands, `deploy-*-stack`, `setup-*-stack` and `delete-*-stack`, are gone, as are those spanning two
clouds: use `manage-stack`, eg `manage-stack delete <stack-id>`, or `manage-stack delete <cloud>-all` for every stack of a cloud,
eg `aws-all`.  What spans stacks is a scenario, eg `manage-stack run aws-full external-azure`, run once the stacks it needs are set up,
or a [flow](#run-a-flow-of-stacks), eg `manage-stack run cross-cloud-azure`, which sets them up first.

### Run a flow of stacks

A flow is a composed run: a named, ordered list of actions on stacks and scenarios, run in order with one command, stopping at the first failure.  `run` takes either a stack id and a scenario, or a flow name, which is never a stack id:
```shell
afon-cli.sh manage-stack run                                      # lists the flows and their items
afon-cli.sh manage-stack run <flow> [<item>:<step>] [--replace] [--dry-run]
```
- `--replace` applies to every `deploy` and `setup` item;
- `--dry-run` lists the steps of the whole flow, grouped by item, without running them;
- `<item>:<step>` resumes from a step, as step names repeat across items.  A failed flow reports the step to resume from, and
  the items that did not run, eg `afon-cli.sh manage-stack run cross-cloud-azure 2:deploy_complete_for_azure_polaris`.

The flows are:
- `aws-full-and-azure-polaris`: `setup aws-full`, `setup azure-polaris`;
- `aws-keycloak-and-polaris`: `setup aws-keycloak`, `setup aws-polaris`;
- `gcp-keycloak-and-polaris`: `setup gcp-keycloak`, `setup gcp-polaris`;
- `azure-and-gcp-polaris`: `setup azure-polaris`, `setup gcp-polaris`;
- `azure-keycloak-and-polaris`: `setup azure-keycloak`, `setup azure-polaris`;
- `cross-cloud-azure`: `setup aws-full`, `setup azure-polaris`, then `run aws-full external-azure`;
- `snowflake-<scenario>`, ie `snowflake-reading-aws`, `snowflake-reading-azure`, `snowflake-reading-gcp` and
  `snowflake-writing-azure`: `setup aws-snowflake`, then `run aws-snowflake <scenario>`;
- `snowflake-reading-all`: just the three reading runs in a row, on an `aws-snowflake` that is already set up.

They are defined in `stack-flows.yaml` (`afon-ops/src/main/resources/stack-flows.yaml`, bundled in the jar), and validated when
used: the stacks, scenarios and actions must exist, a scenario runs on a stack no earlier item deleted (a flow may be just a chain
of runs on stacks already set up), and a later item must not delete a stack an earlier one leaves running, by conflicting with it.
Each flow has a generated document, under `flow_*.md`.

#### Define your own flows

A `stack-flows.yaml` in the config dir (`$AFON_HOME/config`) **replaces** the bundled one entirely, rather than adding to it, so
start from a copy, which this exports there (without overwriting an existing file):
```shell
afon-cli.sh manage-stack run --export-flows
```
Each flow is a name, which is not the id of a stack, and a list of items, each one action (`delete`, `deploy`, `setup` or `run`)
on a stack, and a `scenario` for `run`:
```yaml
flows:
  my-flow:
    - setup: {cloud: aws, role: tre}                 # the stack of the cloud with the role in stacks.yaml
    - setup: {stack: aws-lab}                        # a stack by its id
    - run: {cloud: aws, component: Snowflake, scenario: reading-aws}   # the stack of the cloud that deploys the component
```
A stack is named by its id (`stack`), or by a `cloud` with a `role` (`catalog`, `identity` or `tre`) or a `component` it deploys.
The bundled flows name stacks by role or component, so that they work whatever the ids in `stacks.yaml`. A flow whose stacks
`stacks.yaml` does not define cannot run: it is not listed, and running it fails, naming the missing stack. A file that is not valid, eg an unknown action, role, cloud or
component, a `run` with no scenario, or a repeated flow, makes `manage-stack` fail before doing anything, naming the file and the
flow. The generated documents (`flow_*.md`) are made when Afon is built, from the bundled flows only, so a flow of your own has none:
list its steps with `manage-stack run <flow> --dry-run`.

### Stacks side by side

**NOTE:** The AKS cluster, network and identity of an Azure stack are named `<stack-type>-<name>`, eg `keycloak-aks-vnet` and `keycloak-<CLUSTER_NAME>`, as on AWS, so the stacks of a resource group don't collide.  The GKE cluster, VPC, subnet and router of a GCP stack are likewise named after the stack type, eg `keycloak-<CLUSTER_NAME>`, so the stacks of a project don't either.

Each stack has its own `tofu` state, so stacks run side by side, unless they conflict, ie they hold a common exclusive
resource: the DNS record of a domain name, eg `OIDC_DOMAIN_NAME`, or the pinned egress IP, `AWS_EGRESS_IP`, `AZURE_EGRESS_IP` or `GCP_EGRESS_IP`, which only
one running stack can hold.  So `deploy` and `setup` first delete the stacks deployed in the cloud that conflict with
the stack, including the stack itself.  They only delete the stack itself, if deployed, unless given `--replace`:
if any other stack conflicts, they fail before deleting any, saying what the other stack already deploys, eg
```
Cannot deploy aws-polaris, as stack aws-full is running and already deploys Polaris at emryspolaris.click (AWS_POLARIS_DOMAIN_NAME).
To deploy aws-polaris, delete it first:
    afon-cli.sh manage-stack delete aws-full
or rerun with --replace to delete it as part of this run.
```
Only a deployed stack, ie with a `tofu` state, can make them fail: a conflicting stack dir without one, eg left from `validate-stack`, has nothing deployed, so they just delete it.
The pinned egress IP, `AWS_EGRESS_IP`, `AZURE_EGRESS_IP` or `GCP_EGRESS_IP`, is only held, and so only conflicts, when it is set.
The [AWS stack workflow](github_workflows.md#stack-aws-workflow) always runs with `--replace`.

`delete` deletes just the stack, while `delete <cloud>-all`, eg `delete aws-all`, deletes every stack deployed in the cloud.  The legacy stack of a cloud, deployed before each stack had its own `tofu` state (see
[upgrading to per-stack state](#upgrading-to-per-stack-state-one-off)), may be any stack, so it conflicts with every
stack of the cloud, and `delete` of any stack of the cloud deletes it too.  Likewise, a stack not listed above, eg one deployed from
`validate-stack aws-full` and `tofu-apply`, conflicts with every stack of the cloud.

| Stack | Holds | Runs side by side with |
|---|---|---|
| `aws-keycloak` | `OIDC_DOMAIN_NAME` | `aws-polaris` |
| `aws-polaris` | `AWS_POLARIS_DOMAIN_NAME`, `AWS_EGRESS_IP` | `aws-keycloak` |
| `aws-full` | `OIDC_DOMAIN_NAME`, `AWS_POLARIS_DOMAIN_NAME`, `AWS_JUPYTER_DOMAIN_NAME`, `AWS_EGRESS_IP` | none |
| `aws-full`, with `AWS_KEYCLOAK_EXTERNAL=true` | `AWS_POLARIS_DOMAIN_NAME`, `AWS_JUPYTER_DOMAIN_NAME`, `AWS_EGRESS_IP` | `aws-keycloak` |
| `azure-keycloak` | `OIDC_DOMAIN_NAME` | `azure-polaris` |
| `azure-polaris` | `AZURE_POLARIS_DOMAIN_NAME`, `AZURE_EGRESS_IP` | `azure-keycloak` |
| `azure-full` | `OIDC_DOMAIN_NAME`, `AZURE_POLARIS_DOMAIN_NAME`, `AZURE_JUPYTER_DOMAIN_NAME`, `AZURE_EGRESS_IP` | none |
| `azure-full`, with `AZURE_KEYCLOAK_EXTERNAL=true` | `AZURE_POLARIS_DOMAIN_NAME`, `AZURE_JUPYTER_DOMAIN_NAME`, `AZURE_EGRESS_IP` | `azure-keycloak` |
| `gcp-keycloak` | `OIDC_DOMAIN_NAME` | `gcp-polaris` |
| `gcp-polaris` | `GCP_POLARIS_DOMAIN_NAME`, `GCP_EGRESS_IP` | `gcp-keycloak` |

The Polaris stacks, `aws-polaris`, `azure-polaris` and `gcp-polaris`, deploy no KeyCloak of their own, but use the
KeyCloak server at `OIDC_DOMAIN_NAME`, which the keycloak stack of the cloud, eg `aws-keycloak`, deploys, so deploy (or set up) it first, eg:
```shell
afon-cli.sh manage-stack setup aws-keycloak
afon-cli.sh manage-stack setup aws-polaris
```
Their `deploy` and `setup` check first that KeyCloak answers at `OIDC_DOMAIN_NAME`, and fail fast if it doesn't.

The `aws-full` stack deploys its own KeyCloak, so it conflicts with both `aws-keycloak` and `aws-polaris`, unless
`AWS_KEYCLOAK_EXTERNAL=true` is set in `afon-aws.properties` (it is `false` when empty): it then uses the KeyCloak
`aws-keycloak` deploys, like `aws-polaris`, so it runs side by side with `aws-keycloak`, and its `deploy` and `setup` likewise check first that KeyCloak answers.
Only the Polaris stacks pin their egress IP to `AWS_EGRESS_IP`, if set: `aws-keycloak` always gets a fresh one.  The same holds on Azure with `AZURE_EGRESS_IP`: `azure-keycloak` always gets an address from Azure.  On GCP `gcp-polaris` pins its egress IP to `GCP_EGRESS_IP`, if set, and `gcp-keycloak` always gets addresses from Google.

On Azure it is the same with `azure-keycloak` and `AZURE_KEYCLOAK_EXTERNAL=true` in `afon-azure.properties` (it is `false` when empty):
`azure-full` then uses the KeyCloak `azure-keycloak` deploys, so it runs side by side with it, and its `deploy` and `setup` check first that KeyCloak answers.
The KeyCloak domain `OIDC_DOMAIN_NAME` must then have its DNS zone in the Azure resource group, which `afon-cli.sh azure-create false azure-keycloak` creates.

### A KeyCloak domain name per cloud

`OIDC_DOMAIN_NAME` in `afon.properties` is the domain name of the KeyCloak of every cloud, so the KeyCloak stacks of different
clouds, eg `aws-keycloak` and `azure-keycloak`, conflict, and moving an env from one cloud to another means moving its DNS to the
zone of the new cloud.  To give a cloud a KeyCloak of its own, set `<CLOUD>_OIDC_DOMAIN_NAME`, ie `AWS_OIDC_DOMAIN_NAME`,
`AZURE_OIDC_DOMAIN_NAME` or `GCP_OIDC_DOMAIN_NAME`, in its `afon-<cloud>.properties`.  The domain name of a cloud is that property
if it is set and not blank, else `OIDC_DOMAIN_NAME`, so existing configs behave as before.  Everything of the cloud then uses
it: its KeyCloak stacks, which hold it, so those of two clouds with different domain names no longer conflict; the Polaris stacks that
use that KeyCloak; the DNS zone and certificate of the cloud (each domain needs its own, eg on AWS a hosted zone named after it and
an ACM certificate, see [DNS config](dns_config.md)); and the checks, `check-oidc <cloud>`, which also shows a token with `--token`, and the
status and smoke scripts.

`OIDC_ISSUER_URL` is a template, in which `[OIDC_DOMAIN_NAME]` stands for the domain name of the cloud, so the issuer follows it.  The host of the issuer must be the domain name of the cloud, else the stack
fails to synthesise.

Each cloud's KeyCloak has its own users and tokens, so a client authenticates with the KeyCloak of the cloud hosting the Polaris it
calls, whichever cloud it runs from: the ops commands, which take the cloud, and the smoke tests use the domain name of that cloud, and
a federated (external) catalog, eg `azure_external` of the AWS Polaris, gets its token from the KeyCloak of the cloud hosting the remote
Polaris, here Azure.  The users the flow authenticates as must therefore be configured in that KeyCloak too (`configure-users <cloud>`).

### Upgrading to per-stack state (one-off)

Stacks used to be deployed per cloud: all the stacks of a cloud shared one plan `$AFON_CDKTF/stacks/<cloud>` and one `tofu` state `$AFON_CDKTF/terraform.<cloud>.tfstate`, eg `stacks/azure` and `terraform.azure.tfstate`.  They are now deployed per stack id, each with its own plan `stacks/<stack-id>` and state `terraform.<stack-id>.tfstate`, eg `stacks/azure-polaris` and `terraform.azure-polaris.tfstate`.

A stack deployed before the change, ie with a `terraform.<cloud>.tfstate`, is the *legacy stack* of its cloud.
`manage-stack` cannot tell which stack it is, so it treats it as conflicting with every stack of the cloud.  Hence the first `deploy` or `setup` of any stack of a cloud with a legacy stack fails, before deleting anything, eg:
```
Cannot deploy azure-polaris, as the legacy stack azure, deployed before each stack had its own tofu state, may be any stack.
To deploy azure-polaris, delete it first:
    afon-cli.sh manage-stack delete azure-polaris
or rerun with --replace to delete it as part of this run.
```
This is a **one-off per cloud, per cdktf dir**: once the legacy stack of a cloud is deleted, it is gone for good, and from then on every stack of the cloud has its own state.  Each cdktf env (see [cdktf-switch](#command-cdktf-switch))
has its own legacy stacks, so it happens once in each env.  Either:
- rerun with `--replace`, which deletes the legacy stack and then deploys the stack, eg
  `afon-cli.sh manage-stack setup azure-polaris --replace`, or
- delete it first, as `delete` of any stack of the cloud deletes the legacy stack too, eg
  `afon-cli.sh manage-stack delete azure-polaris`, then deploy the stack as usual.

To see whether a cdktf dir still has legacy stacks, look for `terraform.<cloud>.tfstate` files, where
`<cloud>={aws,azure,gcp}`:
```shell
ls $AFON_CDKTF/*.tfstate
```
A legacy `stacks/<cloud>` dir without a `terraform.<cloud>.tfstate`, eg left from synthesising a stack without
deploying it, has nothing deployed, so it doesn't make `deploy` or `setup` fail: they just delete it.
On AWS, the stacks now also name their components from `<stack>-<CLUSTER_NAME>` rather than `CLUSTER_NAME`, see
[AWS](#aws): the legacy stack keeps its old names until it is deleted, which its own plan and state take care of.
The [AWS stack workflow](github_workflows.md#stack-aws-workflow) runs with `--replace`, so it deletes the legacy stack of its runner's cdktf dir on its first run, without failing.

### Validate a stack's config

To validate a stack's config without deploying it, eg that the config dir has all the properties the stack needs,
you can synthesise just the cdktf plan of a stack, which needs no cloud login:
```shell
afon-cli.sh validate-stack <stack>  # eg azure-polaris or aws-full, run without <stack> to list the stacks
```
This fails fast, eg with `Property X not found`, if the config is incomplete.
It overwrites the plan in `$AFON_CDKTF/stacks/<stack-id>` (ie `cdk.tf.json` and `assets`), but not the state file
`$AFON_CDKTF/terraform.<stack-id>.tfstate`, as it doesn't run tofu.  To leave the plan of a running or previous deploy
of the same stack untouched, synthesise into another cdktf dir, eg `afon-cli.sh --cdktf /tmp/cdktf validate-stack azure-polaris`.

A stack is a list of components, eg Polaris, Keycloak, Jupyter, Spark, Trino, deployed to its cloud: see
[Stack composition](architecture.md#stack-composition) for what each stack is made of, and how to define a new one.

### Define your own stacks

The stacks are defined by `stacks.yaml`, bundled in the jar (`afon-cdk/src/main/resources/stacks.yaml`).  To define them
differently, eg to drop a component from a stack or add a stack, put a `stacks.yaml` in the config dir (`$AFON_HOME/config`).
It **replaces** the bundled one entirely, rather than adding to it, so start from a copy of it, which this exports to the config dir
(without overwriting an existing file):
```shell
afon-cli.sh validate-stack --export-stacks
```
Then edit `$AFON_HOME/config/stacks.yaml`.  Nothing else needs to change:
`validate-stack`, `manage-stack` and its docs (`manage-stack <action> <stack-id> -h`) take their stacks from it.

Each stack is `<cloud>-<name>`, and the ordered list of the components it deploys, each after those before it:
```yaml
stacks:
  aws-lab:
    role: tre                       # optional, see below: the role the stack plays in its cloud, if any
    pinnedEgressIp: true            # optional, default true (or as the stack it extends): holds <CLOUD>_EGRESS_IP, eg AWS_EGRESS_IP, which only one running stack can
    components:
      - name: Keycloak              # deployed unless AWS_KEYCLOAK_EXTERNAL=true, ie the property <CLOUD>_KEYCLOAK_EXTERNAL
        unless: KEYCLOAK_EXTERNAL   # or 'when', to deploy it only if the property is true
      - Polaris
      - Jupyter
      - NetworkPolicies
      - SparkOperator
      - Spark
      - CrdCleanup
  aws-lab-trino:
    extends: aws-lab                # starts with the components of aws-lab, which must be of the same cloud
    components: [Trino]
```
A stack may have a `role`, one of `catalog` (the catalog provider, eg Polaris), `identity` (the identity provider, eg KeyCloak)
or `tre` (the trusted research environment, ie all the components).  The code that depends on what a stack is for, rather than on
its id, looks for the stack of the cloud with the role: the flows of `manage-stack run` (eg `cross-cloud-azure` sets up the `tre`
stack of AWS and the `catalog` stack of Azure), and the checks that say which stack to set up (eg for KeyCloak to answer).  So the
ids are free to name as you like, but a cloud has at most one stack per role, and a flow whose stacks the file does not define
cannot run.  A stack with no role, like `aws-lab`, is managed all the same; the `role` is not inherited by a stack that `extends`
another.

The components are those of the code, eg `Polaris`, `Keycloak`, `Jupyter`, `Spark`, `Trino`; a file cannot define a new one.
The stacks of a cloud are independent of those of the others.

A stack is validated when loaded, and every command fails before doing anything, naming the file and the entry, if the file is not
valid: a YAML syntax error, an unknown component, cloud or property, a malformed or repeated stack id, an `extends` of an unknown
stack, of another cloud or in a cycle, or components composed wrongly (a component repeated, or missing one it needs before it).

Try the new stack, which like the others conflicts with those holding the same domain names or egress IP:
```shell
afon-cli.sh validate-stack aws-lab        # synthesises its plan, and checks that it is the same in each JVM
afon-cli.sh manage-stack setup aws-lab
```
`validate-stack` prints the role of the stack, if it has one, and its components as the file defines them, for the config (those left out by a `when` or
`unless` property are not listed), and without a stack lists the stacks of the file.

### Define your own Docker images

The Docker images that the stacks deploy (Polaris, Keycloak, Spark, JupyterHub, Guacamole, ...) are defined by `docker-images.yaml`,
bundled in the jar, so that a version bump or an image pinned by digest is a change
of one file, not of the code.  As with the stacks, a `docker-images.yaml` in the config dir (`$AFON_HOME/config`) **replaces** the
bundled one entirely, rather than adding to it, so start from a copy of it, which this exports to the config dir (without overwriting
an existing file):
```shell
afon-cli.sh docker-image --export
```
Then edit `$AFON_HOME/config/docker-images.yaml`.  A file that is not valid, eg an unknown
key or category, a malformed digest, or an image the code needs that it does not define, fails the commands that use it, naming the
file and the problem.

Each image is under the name the code looks it up by:
```yaml
images:
  keycloak:
    image: quay.io/keycloak/keycloak    # the repository, without the tag
    tag: 26.6.3
    digest: sha256:<64 hex digits>      # optional: pins the image, which is then pulled as image:tag@digest
    category: upstream                  # optional: first-party, upstream or third-party
  polaris:
    image: apache/polaris
    tag: ${polaris.version}             # the version of Polaris that the project builds against
```
The bundled images are pinned by digest, so that what is pulled is what was pinned, not whatever the tag points to by then (the digest
only fixes the content, it does not say who built it: that is what a signature is for).  The digest is that of the manifest list, so
that it holds for each platform.  An image is pulled by its digest, the tag then only documenting it, so when bumping a tag, update the
digest with it, or the old version is still pulled.  `docker-image --pin` looks up in the registries the digest that each tag points to
and writes it to the file, leaving the rest of the file, comments included, as it is (it needs Docker and network access, and writes
nothing if a lookup fails):
```shell
afon-cli.sh docker-image --pin keycloak       # after changing its tag: pins it, adding the digest if it has none
afon-cli.sh docker-image --pin                # refreshes the digest of each image that has one
```
It pins the `docker-images.yaml` of the config dir, so export the bundled one to it first (`docker-image --export`), unless `--file` gives another.  An image that is not to be pinned says why under `unpinned`,
eg `unpinned: only its tag is used, the image being that of the Helm chart`, in place of its `digest`: `--pin` refuses it with that reason, and
`--verify` lists it as not checked with the reason.  `docker-image --verify` only checks, writing nothing, that the
digest of each image that has one is that its tag points to now, and exits with 1, naming the images, if not: that is, if a tag was
changed without its digest, or published again by its registry.  It checks the images in effect, or those of `--file`, or the named ones:
```shell
afon-cli.sh docker-image --verify
afon-cli.sh docker-image --verify --report verify.md     # also writes a report, as Markdown, or as JSON if named *.json
```
The report is written even when the check fails, for a CI job to keep: it has the result, each image checked with its status
(`ok`, `mismatch`, `unpinned` or `error`) and the pinned and registry digests, and the images not checked with the reason.
The Polaris images are unpinned, as their tag is the version of Polaris the project builds against, which a pinned digest of another
version would outlive, and so is the Trino one, only its tag being set, the image being that of the Helm chart.

The images that the Helm charts of the stacks pull are the charts' own, so they are not in the file.  `docker-image --charts` reports them,
from the plans synthesised for the stacks (`validate-stack <stack-id>` makes one), so with the values the stack gives each chart, eg the
Trino tag it overrides: it renders every chart with `helm template`, which needs helm installed but no cluster, and lists the images of
each release, saying if each is pinned by digest, has a tag only or none, and which the file lists.  The images that only `helm test`
pods use are counted, not listed.
```shell
afon-cli.sh docker-image --charts aws-full azure-full
afon-cli.sh docker-image --charts --report charts.md      # the stacks that are synthesised, also as a report, JSON if named *.json
```

The names are those of the bundled file, which the code needs all of; an entry of another name is accepted but nothing uses it.  The `trino` entry
only sets the image tag, the image being that of the vendored Helm chart.

A shell script that needs the reference of an image asks the ops command `docker-image <name>`, eg `afon-cli.sh docker-image postgres-alpine`
prints `AFON_DOCKER_IMAGE=postgres:16-alpine`, as `scripts/stack/docker-image-source.sh` does for `kube-polaris-db`.  (Run by `afon.sh`,
which has no JVM, the script asks it the same way, a Maven run, so a few seconds slower.)  The system property `afon.docker-images`
names a file to use instead, or `bundled` for only the bundled one.

### Setup AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
afon-cli.sh manage-stack setup aws-full
```
Steps executed:

1. `delete_stacks_conflicting_with_aws_full` — delete the AWS stacks conflicting with it, ie any existing AWS stack
2. `deploy_aws_full_stack` — deploy AWS full stack
3. `wait_on_emryspolaris` — wait for DNS to propagate
4. `create_all_catalogs_for_cloud_aws` — create all catalogs in AWS Polaris
5. `aws_spark_talks_to_cloud_aws` — smoke test: Spark writes and reads via Polaris

### Access Polaris and Jupyter

For development purposes, we've registered for `aws` domains `emryspolaris.click` and `emrysjupyter.click`.

Once the AWS full stack is deployed, we can access Polaris and Jupyter via the URLs:
- `https://emryspolaris.click`
- `https://emrysjupyter.click/guacamole`

### Deploy AWS Keycloak only

The AWS Keycloak stack deploys only the Keycloak server, ie the EKS cluster with Keycloak behind its HTTPS ingress
at `OIDC_DOMAIN_NAME`, without Polaris, eg to serve as a standalone OIDC provider, such as for `aws-polaris`, which
runs side by side with it.

```shell
afon-cli.sh manage-stack deploy aws-keycloak
```
Steps: `delete_stacks_conflicting_with_aws_keycloak` → `deploy_aws_keycloak_stack` → `wait_on_emrysoauth` → `deploy_complete_for_aws_keycloak`

It requires the Keycloak properties (`OIDC_DOMAIN_NAME`, `KEYCLOAK_*`)
and the AWS cluster properties, but none of the `AWS_POLARIS_*` properties.
Once deployed, you can check the OIDC provider is ready with `afon-cli.sh check-oidc <cloud>`.

### Setup AWS Keycloak only

To deploy the AWS Keycloak stack and configure it with the realm, the `POLARIS_ID` client and the users (and
their confidential clients), plus the Jupyter browser client if `AWS_JUPYTER_DOMAIN_NAME` is set:

```shell
afon-cli.sh manage-stack setup aws-keycloak
```
Steps: `delete_stacks_conflicting_with_aws_keycloak` → `deploy_aws_keycloak_stack` → `wait_on_emrysoauth` → `configure_oidc_users_for_cloud_aws` → `setup_complete_for_aws_keycloak`

### Setup Azure full stack

```
afon-cli.sh manage-stack setup azure-full
```

**NOTE:** `azure-full` needs 4 nodes of the default size, not the 3 that are the default: set `AZURE_AKS_NODE_POOL=nodeCount:4` in
`afon-azure.properties` first, else pods such as the Spark Connect driver stay Pending (`Insufficient cpu`).  Check with
`afon-cli.sh kube-estimator azure-full 4`.

**NOTE:** `azure-full` deploys the network policies, which limit what the notebooks and the Spark master and driver can resolve to
the domains of `whitelist.yaml`.  Its `azure` section, as in the template, lists the Azure names: the OIDC, Polaris and Jupyter domain
names and `${AZURE_STORAGE_ACCOUNT}.blob.core.windows.net`, else Spark fails with `UnknownHostException:
<polaris domain>: Temporary failure in name resolution`.  The policies are replaced when the list changes, on the next deploy.

`azure-full` has the same components as `aws-full`, including its own KeyCloak, served at `OIDC_DOMAIN_NAME`, which on Azure must be a domain
whose DNS zone is in `AZURE_RESOURCE_GROUP`, like `AZURE_POLARIS_DOMAIN_NAME` and `AZURE_JUPYTER_DOMAIN_NAME`.  Its network policies
need the cluster's Advanced Container Networking Services, which the stack enables, and its monitoring keeps the logs in the container of the
`azure` catalog, using `AZURE_CONNECTION_STRING`.

### Setup Azure Polaris only

```shell
afon-cli.sh manage-stack setup azure-polaris
```
Steps: `delete_stacks_conflicting_with_azure_polaris` → `check_keycloak_answers` → `deploy_azure_polaris_stack` → `wait_on_polaris_and_keycloak_for_cloud_azure` → `create_all_catalogs_for_cloud_azure`

### Setup GCP Polaris only

```shell
afon-cli.sh manage-stack setup gcp-polaris
```
Steps: `delete_stacks_conflicting_with_gcp_polaris` → `check_keycloak_answers` → `deploy_gcp_polaris_stack` → `wait_on_polaris_and_keycloak_for_cloud_gcp` → `create_all_catalogs_for_cloud_gcp`

### Setup cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying,
run the [flow](#run-a-flow-of-stacks) `cross-cloud-azure`, which sets up both stacks, then runs the scenario `external-azure`
of the AWS stack:

```shell
afon-cli.sh manage-stack run cross-cloud-azure --replace
```

The same, one command at a time, resuming or re-running just the part needed:

```shell
afon-cli.sh manage-stack setup aws-full --replace
afon-cli.sh manage-stack setup azure-polaris --replace
afon-cli.sh manage-stack run aws-full external-azure
```

The scenario starts by checking that the Azure Polaris answers, and fails, saying to set up `azure-polaris`, if it doesn't.  It then
deletes the test table of the `azure` catalog, creates the `azure_external` catalog, and checks that Spark writes through the AWS
Polaris to it.  It runs on any AWS stack with Spark, eg `aws-full` or `aws-snowflake`.

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, by adding it to the command, e.g.:

```shell
afon-cli.sh manage-stack setup aws-full create_all_catalogs_for_cloud_aws
afon-cli.sh manage-stack run aws-full external-azure create_catalog_azure_external_for_cloud_azure
```

### Teardown a deployed stack

The stack teardown can be done via replacing `deploy` with `delete` in the command, eg:
```shell
afon-cli.sh manage-stack delete aws-full
afon-cli.sh manage-stack delete azure-polaris
afon-cli.sh manage-stack delete gcp-polaris
```

The `delete` action is equivalent to running `tofu destroy` on the appropriate `$AFON_CDKTF` dir: it deletes just the
stack, and `delete <cloud>-all` deletes every stack deployed in the cloud, see [stacks side by side](#stacks-side-by-side).

### The Afon cdktf dir

The afon-cdk module generates the Terraform plan using the cdktf API to manage the cloud environment and delegates it to tofu to execute it.   The afon-cdk module writes to the cdktf dir, which can be set either via option `--cdktf` in `afon.sh` or via env variable `AFON_CDKTF`.  It defaults to `$AFON_HOME/cdktf`.

Each stack has its own cdktf plan and Terraform state, named by its stack id `<cloud>-<name>`, eg `aws-keycloak`:
the cdktf plan is written to `$AFON_CDKTF/stacks/<stack-id>` and the cdktf dir also holds the Terraform state files
`$AFON_CDKTF/terraform.<stack-id>.tfstate`.

Each AWS stack also has its own kubeconfig `$AFON_CDKTF/kube/<stack-id>.config`, eg `kube/aws-keycloak.config`,
written when its EKS cluster is created and deleted with the stack.  All the kubectl and helm commands the stack runs,
eg the cleanups when it is destroyed, use it via `KUBECONFIG`, so they only ever act on the stack's own cluster,
whichever cluster your current kubectl context points at.  Creating the cluster also points your current kubectl context
(`~/.kube/config`) at it, as `afon-cli.sh aws-update-kube-config <stack-id|role>` does, eg for the smoke tests.

**NOTE:** Before each stack had its own, the stacks of a cloud shared the plan `$AFON_CDKTF/stacks/<cloud>` and state `$AFON_CDKTF/terraform.<cloud>.tfstate`, where `<cloud>={aws,azure,gcp}`.  A stack still deployed with these is the legacy stack of its cloud, see [upgrading to per-stack state](#upgrading-to-per-stack-state-one-off).

You can run tofu apply or destroy commands via
```shell
afon-cli.sh tofu-{apply,destroy}
```
Given a stack id as first arg, eg `afon-cli.sh tofu-plan aws-polaris`, or its role in the cloud as `<cloud>-<role>`, eg `afon-cli.sh tofu-plan aws-tre`
(looked up in `stacks.yaml` by `afon-cli.sh` and `afon.sh` alike), they run in its stack dir.  Else they run in
the dir of the only stack deployed in the cloud given by `--cloud` (default `aws`), ie the stack dir with a Terraform
state, else the only stack dir of the cloud.
**NOTE:** The `ops.Deploy*Main` classes generate the plan and execute it without needing the `tofu-apply` execution.
You may re-run the `tofu-apply` if needed.

To validate a stack's config without deploying it, eg that the config dir has all the properties the stack needs,
you can synthesise just the cdktf plan of a stack, which needs no cloud login:
```shell
afon-cli.sh validate-stack <stack>  # eg azure-polaris or aws-full, run without <stack> to list the stacks
```
This fails fast, eg with `Property X not found`, if the config is incomplete.
It overwrites the plan in `$AFON_CDKTF/stacks/<stack-id>` (ie `cdk.tf.json` and `assets`), but not the state file
`$AFON_CDKTF/terraform.<stack-id>.tfstate`, as it doesn't run tofu.  To leave the plan of a running or previous deploy
of the same stack untouched, synthesise into another cdktf dir, eg `--cdktf /tmp/cdktf`.

#### Command cdktf-switch

To support multiple config envs, say a `dev` and a `stg` env, you can use the `cdktf-switch` action:
```shell
afon-cli.sh cdktf-switch -e {dev|stg} # cdktf symlink for <env> 
```
This will create or move the symlink `$AFON_CDKTF` pointing to `$AFON_CDKTF-<env>`.

**NOTE**: If you're using multiple config envs, the cdktf dir must be a symlink and not a dir.  
Create cdktf env dirs `$AFON_CDKTF-<env>` and ensure there is no cdktf dir.   
The cdktf-switch action will remove any existing symlink.

#### Command cdktf-status

To show the status of the current cdktf dir, you can use the `cdktf-status` action:
```shell
afon-cli.sh cdktf-status
```
If found, it will output the content of the file `manifest.json` in the cdktf dir, which can be used to
provide information to the user of the cdktf content.  It will also list the files in the cdktf dir.

## Stack teardown 

The stack can be brought down using tofu, which will use the locally stored tfstate.
```shell
afon-cli.sh tofu-destroy
```

## Recovery

If things go astray, you may leave the stack in an unstable state.
Use the cleanup helpers to bring your environment to a clean and stable state:

```shell
afon-cli.sh generate-cleanup-script <stack-id>  # eg aws-keycloak, generating $AFON_HOME/aws-keycloak-cleanup.sh
```
You can run the generated cleanup scripts.
You may need to run them multiple times for the VPC to disappear from your cloud.

### The delete checks that nothing is left before it deletes the state

`manage-stack delete` (and the delete that `deploy` and `setup` do with `--replace`) runs `tofu destroy`, then **checks the tofu state itself**
before deleting it and the stack dir. It reads the state file and lists the resources that it still has as deployed. Only the resources that outlive the
stack are allowed to remain, ie the Gitea volume (see [Volumes that outlive the cluster](cluster.md#volumes-that-outlive-the-cluster)); data sources
are not counted.

- **Nothing left:** the state and the stack dir are deleted, as before.
- **Resources left, or the state cannot be read:** the delete fails, naming up to 15 of the resources, and **keeps the state and the stack dir**. Run the
  delete again. If the same resources are still there, look at why, in the stack dir (`$AFON_CDKTF/stacks/<stack-id>`):

```shell
tofu state list     # what the state still has
tofu destroy        # try again, and read what stops it
tofu state rm '<address>'   # only for a resource you know is gone from the cloud
```

The check is there because `tofu destroy` can finish successfully without having destroyed what is in the state, eg after it was interrupted or when
it reports `0 destroyed`. Deleting the state then leaves the stack running in the cloud with nothing to destroy it, which costs money, and deploying
the stack again fails with `already exists` (an EKS cluster, a VPC, an IAM role). The state is checked, not what tofu says.

It cannot help if the state had already lost the resources before the delete, eg after a `tofu state rm` or by deleting the state file by hand: then use
the cleanup script above, or delete the leftovers in the cloud.

### AWS

The AWS components of each stack are named from its cluster name `<stack>-<CLUSTER_NAME>`, where `<stack>` is the stack id without the cloud, eg
`keycloak-emrys-cluster` for stack `aws-keycloak`: its EKS cluster, its VPC `cdktf-eks-vpc-<cluster-name>`, its subnets,
its IAM roles and so on.  The AWS scripts acting on a stack are given the stack, either by its id, eg `aws-full`, or by its role
in the cloud in `stacks.yaml`, ie `identity`, `catalog` or `tre`, which `afon-cli.sh` and `afon.sh` resolve to the stack and its cluster name, eg:
```shell
afon-cli.sh aws-cluster identity             # is the EKS cluster up?
afon-cli.sh aws-update-kube-config tre       # point kubectl at the EKS cluster
```
Checking Polaris or KeyCloak needs no stack, as only one of the stacks serving each runs at a time: it is found
by its EKS cluster among the stacks that deploy it, which `afon-cli.sh` and `afon.sh` take from `stacks.yaml`, unless given, eg `aws-polaris tre` or
`aws-keycloak identity`:
```shell
afon-cli.sh aws-polaris                      # is Polaris serving?
afon-cli.sh aws-keycloak                     # is KeyCloak serving?
```
They check each layer in turn, one line each: the login, the stack's VPC and EKS cluster, its pod and ingress, then
real HTTPS requests, anonymously and as the Polaris admin (`POLARIS_ID`).  For KeyCloak these are its master realm,
which shows it serving, its realm `polaris`, which shows it set up, and a token for the Polaris admin.  The stacks
serving KeyCloak are those that deploy it, which the stacks with their own KeyCloak do not if `AWS_KEYCLOAK_EXTERNAL=true`.
The IAM role names are limited to 64 chars, so `CLUSTER_NAME` must be short enough for
`AmazonEKSLoadBalancerControllerRole<stack>-<CLUSTER_NAME>`, which a stack checks when it is synthesised.

**NOTE:** Before each stack had its own cluster name, the components were named from `CLUSTER_NAME` alone.

To check the AWS VPC has been destroyed:

```shell
afon-cli.sh aws-vpcs             # the VPCs of every stack of this env, same as: afon-cli-scripts.sh aws/vpcs.sh
afon-cli.sh aws-vpcs <stack-id|role>  # the VPC of the stack, by its id or its role, eg identity
```

And if all else fails, login to the [AWS VPC Console](https://eu-west-2.console.aws.amazon.com/vpcconsole/home?region=eu-west-2#vpcs:)


### Azure

The Azure components of each stack are named from its id, like the AWS ones: its AKS cluster is `<stack>-<CLUSTER_NAME>`, where `<stack>` is the stack id without the cloud, eg
`keycloak-emrys-cluster` for stack `azure-keycloak`, and its network and identity `<stack>-aks-vnet` and `<stack>-aks-identity`.
So the Azure scripts acting on a stack are given the stack, by its id or its role (`identity`, `catalog` or `tre`), as on AWS, eg:
```shell
afon-cli.sh azure-cluster identity             # is the AKS cluster up?
afon-cli.sh azure-update-kube-config tre       # point kubectl at the AKS cluster
```
Checking Polaris or KeyCloak needs no stack either, as only one of the stacks serving each runs at a time: it is found by its AKS
cluster in `AZURE_RESOURCE_GROUP` among the stacks that deploy it, unless given, eg `azure-polaris tre` or `azure-keycloak identity`:
```shell
afon-cli.sh azure-polaris                    # is Polaris serving?
afon-cli.sh azure-keycloak                   # is KeyCloak serving?
```
They check the same layers as on AWS.  The stacks serving KeyCloak are those that deploy it, which the stacks with their own KeyCloak do not if `AZURE_KEYCLOAK_EXTERNAL=true`. Polaris is called through a port-forward to its pod, unless `POLARIS_ACCESS=https`, see [reaching Polaris from the setup steps](cluster.md#reaching-polaris-from-the-setup-steps).

#### Estimating the capacity of the cluster

A pod is placed on a node by its CPU and memory *requests*, so one that no node has the requests left for stays Pending
(`FailedScheduling: ... Insufficient cpu`) until the Helm release it belongs to times out, although the cluster is
mostly idle.  Before deploying, estimate whether a stack fits on the nodes, from its synthesised plan and the requests
of its pods and Helm charts, eg with the 4 nodes that `azure-full` needs, configured by `AZURE_AKS_NODE_POOL=nodeCount:4`:
```shell
afon-cli.sh validate-stack azure-full
afon-cli.sh kube-estimator azure-full 4 Standard_D2s_v3
```
It lists the pods by what they request, where each would be placed, and which would stay Pending, in which case it exits 1.
It also lists the pods that request nothing, which the scheduler counts as free although they use CPU and memory, so a stack that
fits is still tighter than it looks.  `ALLOC_CPU` and `ALLOC_MEM` (m and Mi) give the allocatable resources of a node of a size
it doesn't know.

When the Azure resource group is stuck:

```shell
az group delete --name afon_resource_group_aks --yes --no-wait
```

### GCP

The GKE cluster of a GCP stack is named `<stack-type>-<CLUSTER_NAME>`, eg `polaris-<CLUSTER_NAME>`, as on AWS and Azure, so that
`gcp-keycloak` and `gcp-polaris` run side by side.  **NOTE:** a `gcp-polaris` deployed before the stacks were named so has a cluster, VPC,
subnet and router named `<CLUSTER_NAME>` alone, which a deploy of the new code replaces: delete it with the previous version first.
The scripts acting on a stack are given it by its id or its role (`identity` or `catalog`), eg:
```shell
afon-cli.sh gcp-update-kube-config catalog     # point kubectl at the GKE cluster
afon-cli.sh gcp-polaris                        # is Polaris serving?
afon-cli.sh gcp-keycloak                       # is KeyCloak serving?
afon-cli.sh gcp-create identity                # create the env (DNS zones, buckets, service accounts) for the stack
```
`gcp-create` needs the stack, as the DNS zones it creates are those of the domain names the stack serves, and `gcp-issue-cert` issues
the certs of every configured domain name (`GCP_POLARIS_DOMAIN_NAME`, `GCP_JUPYTER_DOMAIN_NAME` and `OIDC_DOMAIN_NAME`).
`gcp-update-kube-config` needs `gke-gcloud-auth-plugin` for kubectl to reach the cluster, and warns if it is missing: install it with
`afon-cli.sh local-setup -c gcp`.

## Summary of deployment commands

```shell
# Any stack: <action>={delete|deploy|setup}, <stack-id>=<cloud>-<name>, eg aws-keycloak
afon-cli.sh manage-stack <action> <stack-id>

# AWS full stack (Keycloak + Polaris + Spark + Jupyter)
afon-cli.sh manage-stack deploy aws-full

# AWS Keycloak (standalone)
afon-cli.sh manage-stack deploy aws-keycloak

# Azure Polaris (standalone)
afon-cli.sh manage-stack deploy azure-polaris

# GCP Keycloak (standalone)
afon-cli.sh manage-stack deploy gcp-keycloak

# GCP Polaris (standalone)
afon-cli.sh manage-stack deploy gcp-polaris

# Cross-cloud external catalogs (AWS + Azure federation): the flow sets up both stacks, then runs the scenario
afon-cli.sh manage-stack run cross-cloud-azure

# Any flow, ie a named list of actions and scenarios: lists the flows without a name
afon-cli.sh manage-stack run [<flow>]

# Snowflake: the flow sets up the stack, then runs a scenario, ie one of reading-aws, reading-azure, reading-gcp, writing-azure
afon-cli.sh manage-stack run snowflake-reading-gcp
afon-cli.sh manage-stack run snowflake-writing-azure
# or, with the stack set up, the reading scenarios in a row
afon-cli.sh manage-stack run snowflake-reading-all
```

## Deployment checklist

- [ ] **Domain names** purchased via your domain registrar, one per cloud (e.g. `polaris.yourdomain.com`)
- [ ] **DNS zones** created in each target cloud and nameservers configured at your domain registrar
- [ ] **SSL certificates** requested and validated (ACM for AWS, Let's Encrypt for others)
- [ ] **External Polaris database** provisioned for Polaris metadata storage
- [ ] **OIDC provider** configured OIDC provider for Jupyter access
- [ ] **Cloud IAM roles** created via `scripts/<cloud>/create.sh`
- [ ] **Storage buckets** created (primary, external, and forbidden for each cloud)
- [ ] **Configuration** properties and users values populated in `config` dir
- [ ] **Kubernetes cluster** creation permissions confirmed
