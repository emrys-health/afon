## CLI Release workflow

The [CLI Release](../.github/workflows/cli-release.yml) workflow releases the afon-cli zip (not the Maven jars/poms) as a GitHub
Release: the rolling `snapshot` pre-release for a `SNAPSHOT` version, or the release `<version>` for a release tag.
Unlike the others it runs on a GitHub-hosted runner and needs no secrets: it is triggered by pushes to `main` that change the
modules, scripts or templates, and by the release tag `afon-<version>` pushed by `release.sh`.  It can also be run by hand
for a branch or tag.
See [CLI Release workflow](release.md#cli-release-workflow).

## Maven Release workflow

The [Maven Release](../.github/workflows/maven-release.yml) workflow deploys the Maven artifacts to GitHub Packages,
for a `SNAPSHOT` version of `main` or for a release tag.  Like CLI Release, it runs on a GitHub-hosted runner and needs
no secrets.  Its push triggers are disabled for now: it is run by hand for `main` or a tag.
See [Maven Release workflow](release.md#maven-release-workflow).

## Smoke tests workflows

The `smoke-tests-<cloud>` workflows, [AWS](../.github/workflows/smoke-tests-aws.yml), [Azure](../.github/workflows/smoke-tests-azure.yml) and [GCP](../.github/workflows/smoke-tests-gcp.yml), each run
`afon-cli.sh smoke-test <cloud> --setup <cloud>-full --teardown` in the [afon-cli Docker image](docker.md), on a **GitHub-hosted
runner**: they deploy and configure the full stack of the cloud, run the smoke tests against it, and tear it down, whether the tests
passed or failed.  See [Smoke tests](smoke_tests.md).  Each is only the trigger and the cloud: they all call the reusable workflow
[Smoke Tests](../.github/workflows/smoke-tests.yml), which holds the steps, so a change to them is made there, once.
Their Slack notifications and their recovery are per workflow, ie per cloud.

They are meant to run downstream of a new build of afon-cli, ie when the [CLI Release](release.md) workflow completes successfully on
`main`, which has also published the images of that build, each testing the image of the project version (eg `1.0-SNAPSHOT`).  Run by
hand, they test the image tag given by `image_tag`.  They run concurrently, each on a runner of its own: a hosted runner is a new VM for
each job, destroyed after it, so nothing of a run (config, tofu state, kubeconfigs, logs) is left on a machine, and the repo, being
public, never runs a workflow on a machine of ours.  A run of a cloud waits for the previous one of the same cloud (concurrency group
`smoke-tests-<cloud>`), as both use the same stack.

**The trigger is active:** each workflow runs when the CLI Release workflow completes successfully on `main`, ie after it has published the
images, with the config env of the repository variable `SMOKE_TESTS_CONFIG_ENV`, else `emrys3`, the env of the smoke tests, and
the tag of its config snapshot in the vault, the variable `SMOKE_TESTS_CONFIG_TAG`, else `current`.  They can also be run by hand (`workflow_dispatch`), with the inputs `config_env`, `config_tag`
and `image_tag`.  To deactivate the trigger, comment out the `workflow_run` block of the three callers.

### Where the secrets are

- **Cloud credentials: none are stored.**  The job logs in with a short-lived OIDC token (see [GitHub as the identity provider](#github-as-the-identity-provider)) as the identity that the GitHub environment
  `smoke-tests-<cloud>` is trusted for (an AWS role, an Azure federated credential, a GCP workload identity).
  Create the environment `smoke-tests-<cloud>` for each cloud, limit its deployment branches to `main` and, if wanted, add required
  reviewers; and trust only its subject in the cloud, eg `repo:<owner>/afon:environment:smoke-tests-aws`.  Never run these workflows on
  `pull_request` or `pull_request_target`.  Create the environments and set their variables with [`github-cloud-env`](#github-side):

  | Cloud | Variables |
  |---|---|
  | `aws` | `AWS_ROLE_ARN`, `AWS_REGION` |
  | `azure` | `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, and `AZURE_KEY_VAULT_NAME` if the vault is not `<env>-afon-key-vault` |
  | `gcp` | `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_PROJECT_ID` |

  The identity is the one that deploys the stack and reads the vault, so it should be one of a **dedicated testing cloud account**
  (AWS account, Azure subscription or GCP project), with test-only credentials in the config: what a compromised workflow can reach is limited by that, not by the runner.
  The login lasts for the role's session on AWS (the workflow asks for 2 hours, so the role's maximum session must allow it) and about an
  hour on Azure and GCP, which a run of 45 minutes is within, but not a much longer one.
- **The config** is not held in secrets: the job pulls it from the vault of its own cloud, with
  `afon-cli.sh --cloud <cloud> --env <env> config-vault pull <tag> --force` (see
  [Command config-vault](configuration.md#command-config-vault)): AWS Secrets Manager, Azure Key Vault or GCP Secret Manager.  Push the
  snapshot to test to the store of each cloud, with `afon-cli.sh config-vault push <tag> -e <env> -c <cloud>`.  The region, vault and
  project that the pull needs come from the variables above, as there is no config yet to read them from.  The identity needs to read the vault only: `secretsmanager:GetSecretValue` and `ListSecrets` on AWS, Key Vault Secrets User on Azure, Secret Manager Secret Accessor on GCP.  GitHub masks only the secrets it knows, so the job then masks the values of `secret*.properties` and `catalogs*.properties` (`::add-mask::`) so that none shows in the public log; this is a safety net, as the CLI keeps secrets out of its output itself.
- **The tofu state** is in the cdktf dir of the container, and holds secrets in plain text.  The config of the env must set
  `CDKTF_STORE_ENABLED=true` (the job refuses to deploy without it), so that `manage-stack` stores the state and plan of the stack in the [cdktf store](stacks.md#command-cdktf-store) of the cloud after each deploy, whether it succeeds or not, and deletes them after the delete.
  The store is a private, encrypted bucket (container on Azure) of the testing cloud account, which should be created once by hand
  (`afon-cli.sh cdktf-store push`) so that the identity needs no right to create buckets.  Give only the object rights to the identity
  and to the few who may read it, and expire the old versions of the objects by a lifecycle rule, as the versions of the state hold
  its secrets.
- **Artifacts** are only the results file of the run (`results-<cloud>-full-<time>.txt`): the logs of a public repo are public, and so
  are its artifacts, so the log file is not uploaded.
- **The logs of the run** are stored, whatever its result, by the last step (`logs-store push <run id>-<attempt>`), gzipped, as `<env>/<run id>-<attempt>/`
  in a store of their own, the bucket or container `afon-logs`, apart from the state (`afon-cdktf`), as they need different access: the job only
  writes to it, and an admin reads it.  The log file of the container is lost with it, and the Actions log only has the console output.  From a machine logged in to
  the cloud, `afon-cli.sh logs-store list -e <env>` lists the runs and `afon-cli.sh logs-store pull <run id>-<attempt> -e <env>` writes them to
  `~/.afon/logs/<run id>-<attempt>/`.  The store is created by a first `logs-store push` of an admin who may create buckets (the job's identity may not), and then
  `github-cloud-identity create` is run again on GCP.  If the cloud login has expired by then, the step only warns.
- **Console logging is off**, so the deploy output is not in the Actions log, which is public.  The logs are in the logs store, see above.

### If the job is stopped

The tests step has a limit of 60 minutes, inside the job's 100 minutes, as the teardown step always runs `manage-stack delete <cloud>-full` if the stack is left up (its state holds resources).  

If the job itself is killed, eg a cancel, the VM goes with its state, but the cdktf store has what the last deploy stored: from a machine with access to the testing cloud account, run 
```shell
afon-cli.sh -e <env> cdktf-store pull current <stack-id>
afon-cli.sh tofu-init <stack-id>
afon-cli.sh tofu-destroy <stack-id>
```

What is lost is a deploy that was killed during the apply, as the state is stored when it ends, so resources it had created are not
known: look for the resources of the testing cloud account that are older than a few hours.  From a machine logged in to the testing cloud account (`afon-cli.sh <cloud>-login`), run
```shell
afon-cli.sh github-cloud-resources <cloud>
```
It lists, without deleting, the resources that the stack `<cloud>-full` names after its cluster (`full-<CLUSTER_NAME>`): by default those older than 3 hours, `--older-than <hours>` changes it; the cluster name (`CLUSTER_NAME`) and the region, resource group or project are those of the active config.  It exits with 1 if it found any, so that it can also run as a scheduled check.  Delete what it lists by hand.  A scheduled job that deletes them would close the gap, as would a remote tofu backend, which writes the state as it goes; neither exists yet.

### Pause, resume or start the smoke tests

[github-workflow](../scripts/github/workflow.sh) wraps the `gh workflow` commands for the `<name>-<cloud>` workflows, `--name` being `smoke-tests` by default, and `--cloud all` applying to the workflows of every cloud, in the repo `emrys-health/afon` unless `--repo` (or `GH_REPO`) says otherwise:
```shell
afon-cli.sh github-workflow list                                   # all the workflows, and whether they are disabled
afon-cli.sh github-workflow disable --cloud all                    # pause: no run starts, by a build or by hand
afon-cli.sh github-workflow enable --cloud all                     # resume
afon-cli.sh github-workflow view --cloud azure                     # the workflow and its recent runs
afon-cli.sh github-workflow run --cloud azure --field config_env=emrys3 [--ref <branch>]
```
Disabling does not stop a run in progress, which `gh run cancel` does, but see [if the job is stopped](#if-the-job-is-stopped).  `--dry-run` says what would be run.

## GitHub as the identity provider

The [smoke tests workflows](#smoke-tests-workflows) log in to the clouds without any stored credential, using the OIDC token that GitHub issues to each job.  The cloud does not trust the runner or its machine: it trusts **GitHub as an identity provider**, and a rule that you set up in the cloud, saying which jobs of which repo may act as which identity.

### How it works

1. A job that has `permissions: id-token: write` can ask GitHub for a token.  GitHub provided login action of each cloud ([`aws-actions/configure-aws-credentials`](https://github.com/aws-actions/configure-aws-credentials), [`azure/login`](https://github.com/Azure/login), [`google-github-actions/auth`](https://github.com/google-github-actions/auth)).

2. GitHub returns a [JWT](https://datatracker.ietf.org/doc/html/rfc7519) signed by it, whose claims describe the job:

   | Claim | Value, eg |
   |---|---|
   | `iss` | `https://token.actions.githubusercontent.com` |
   | `aud` | the audience the cloud expects, see below |
   | `sub` | `repo:<owner>/afon:environment:smoke-tests-aws` |
   | `repository`, `ref`, `environment`, `workflow`, `job_workflow_ref` | `<owner>/afon`, `refs/heads/main`, `smoke-tests-aws`, ... |

   The `sub` is `repo:<owner>/afon:environment:<name>` for a job that runs in a GitHub environment, which these jobs do (`smoke-tests-<cloud>`), else `repo:<owner>/afon:ref:refs/heads/<branch>`.  The environment name is case-sensitive.  A repo can instead have the immutable ids of its owner and name in the `sub`, as new repos do, eg `repo:<owner>@<owner-id>/afon@<repo-id>:environment:smoke-tests-aws`: `github-cloud-identity` reads what the repo uses with `gh` (`gh api repos/<owner>/afon/actions/oidc/customization/sub`) and limits the trust to that.
3. The action sends the token to the token service of the cloud, which checks GitHub's signature with GitHub's public keys, and the `iss`, `aud` and `sub` against the rule that you configured.  If they match, it returns **short-lived credentials** for the identity of the rule (a role, an app registration or a service account), and the job uses them like any login: nothing is stored in GitHub, only the identifiers of the identity, as variables of the environment.

So the identity is the role, app or service account that you create, in the testing cloud account.  The security rests on **who can produce the
`sub` that the rule trusts**:
- Only a job that runs in the environment `smoke-tests-<cloud>` gets that `sub`.  Restrict the environment's deployment branches to `main` (Settings > Environments), and optionally require reviewers.
- Whoever can merge a workflow change into `main` can use the identity, so protect `main` and `.github/workflows/` with review (eg CODEOWNERS).
- A workflow run from a fork cannot request the token, which is another reason not to run these on `pull_request` or `pull_request_target`.
- Optionally also require the `job_workflow_ref` claim to be `<owner>/afon/.github/workflows/smoke-tests.yml@refs/heads/main` in the rule, so that no other workflow can use it (AWS, Azure and GCP all can condition on claims; the way differs).

The commands below are run **by an admin, from a terminal with `afon-cli.sh`, once per cloud and before the first run of the workflows**: no workflow runs them, since the identity they create is what lets a job log in at all.  

The admin needs admin rights on the **testing cloud account** (AWS account, Azure subscription or GCP project, not a production one) for the cloud side, and on the GitHub repo for the GitHub side.  `<owner>` is the GitHub owner of the repo, and the environment names are `smoke-tests-aws`, `smoke-tests-azure` and `smoke-tests-gcp`.

### Order 

Set up the cloud side first, then the GitHub side.  

Neither script calls the other, and the trust is only checked when a job logs in, so no step needs the other to exist; but the GitHub side needs identifiers that the cloud side produces:

- AWS: the role ARN is predictable (`arn:aws:iam::<account-id>:role/afon-smoke-tests` by default), so either order would do, but it is simpler to take it from the output.
- Azure: the app registration, whose client id is set on the environment, is only there once it is created, so the cloud side must come first.
- GCP: the names follow from the project, so either order works.

`github-cloud-identity create` ends by printing the `github-cloud-env create` command with those identifiers, so running the two in turn needs nothing to be copied by hand.

Both sides must be in place before the first run of a workflow: without the GitHub side the login step has empty variables, and without the cloud side the cloud refuses the token.  See [If the login fails](#if-the-login-fails).

### Cloud-side

[github-cloud-identity](../scripts/github/cloud-identity.sh) creates the identity that the GitHub job logs in as, its trust and its permissions.  

Log in first with `afon-cli.sh <cloud>-login` (`<cloud>={aws,azure,gcp}`), as an admin of the **testing cloud account**.  The config of the env must be in place, as the login selects its account, subscription or project.

To set up the identity of a cloud:
```shell
afon-cli.sh github-cloud-identity create <cloud>
```
It acts on the account or subscription of the login, and reads the rest from the active config: on Azure the Key Vault (`AZURE_KEY_VAULT_NAME`) and the storage account (`AZURE_STORAGE_ACCOUNT`), on GCP the project (`GCP_PROJECT_ID`).  To set up another one, switch the env (`afon-cli.sh --env <env>`) and log in again; on AWS the login is checked against `AWS_ACCOUNT_ID`.
To delete it, when no longer required (the shared OIDC provider of AWS and workload identity pool of GCP are left):
```shell
afon-cli.sh github-cloud-identity delete <cloud> --force
```
`create` can be repeated, `--dry-run` reports what it would write (the checks that read the cloud still run) and `show <cloud>` reports the identity and its trust.  The trust is limited to the subject
`repo:<owner>/afon:environment:smoke-tests-<cloud>`, or with the ids of the owner and repo if the repo uses them (`--repo` for another repo; `gh` must be logged in, to read it), and it ends by printing the [`github-cloud-env`](#github-side) command with the identifiers to set on the GitHub environment.  The identity is called `afon-smoke-tests` (`--name`).

**The deploy permissions are broad by default**, as the full stacks create networks, clusters and IAM: `AdministratorAccess` on AWS, `Contributor` and `User Access Administrator` on Azure (the stack creates role assignments) and `roles/owner` on GCP.  `--deploy-role` (repeatable) replaces them with narrower ones.

What `create` creates on each cloud:

| | `aws` | `azure` | `gcp` |
|---|---|---|---|
| Identity | IAM role `afon-smoke-tests`, with a 2-hour maximum session, as the workflow asks for | app registration `afon-smoke-tests` and its service principal | service account `afon-smoke-tests@<project-id>.iam.gserviceaccount.com` |
| Trust | the IAM OIDC provider of GitHub (once per account), and the role's trust policy: `sts:AssumeRoleWithWebIdentity` for audience `sts.amazonaws.com` and the subject | a federated credential for issuer `https://token.actions.githubusercontent.com`, audience `api://AzureADTokenExchange` and the subject | the APIs enabled; a workload identity pool and provider `github` with the attribute condition `assertion.sub == <subject>`; that subject may impersonate the service account |
| Deploys | the managed policies of `--deploy-role` | the roles of `--deploy-role` on the subscription | the roles of `--deploy-role` on the project |
| Reads the config vault | inline policy: `GetSecretValue` on `afon/<env>/*` (`--config-env`, else `afon/*/*`) and `ListSecrets`, which cannot be limited to a resource | Key Vault Secrets User on the Key Vault `AZURE_KEY_VAULT_NAME` (the Key Vault must use Azure RBAC) | `roles/secretmanager.secretAccessor` to read and `roles/secretmanager.viewer` to find a snapshot by its tag |
| Uses the cdktf store | inline policy: the objects of the bucket `afon-cdktf-<account-id>` | Storage Blob Data Contributor on the storage account `AZURE_STORAGE_ACCOUNT` (the data role, which Owner and Contributor do not include), which covers the containers `afon-cdktf` and `afon-logs` | `roles/storage.objectAdmin` on the bucket `afon-cdktf-<project-id>`, if it exists |
| Writes its logs | inline policy: `PutObject` (and `ListBucket` to find the bucket) on `afon-logs-<account-id>` | as above | `roles/storage.objectCreator` and `roles/storage.legacyBucketReader` on the bucket `afon-logs-<project-id>`, if it exists |
| Secrets for GitHub (and the variable `AWS_REGION`) | `AWS_ROLE_ARN` | `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_KEY_VAULT_NAME` | `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_PROJECT_ID` |

The cdktf store is created by a first `cdktf-store push` (see [Command cdktf-store](stacks.md#command-cdktf-store)), by an admin who may create buckets.  On GCP, if its bucket does
not exist when `create` runs, the permission on it is skipped with a message, so run `create` again once it does; on Azure, without a Key Vault or a storage account, from the config or the options, the
corresponding permission is skipped likewise.

To do the same without the script, create these in the console or CLI of the cloud; `--dry-run` prints the commands it would run.

### GitHub-side

[github-cloud-env](../scripts/github/cloud-env.sh) creates the environment `smoke-tests-<cloud>` (limited to deployments from `main`) and sets the variables of the [table above](#where-the-secrets-are).  

Run it as an admin of the repo, with `gh` logged in; `github-cloud-identity create` prints the command to run.

To set up the environment of a cloud:
```shell
afon-cli.sh github-cloud-env create <cloud>
```
As for the cloud side, the variables are read from the active config or found by the name of the identity, not given as options: on AWS the region (`AWS_REGION`) and the role `afon-smoke-tests` of the account (`AWS_ACCOUNT_ID`), on Azure the app registration `afon-smoke-tests`, the tenant (`AZURE_TENANT_ID`), the subscription of the az login and the Key Vault (`AZURE_KEY_VAULT_NAME`), on GCP the project (`GCP_PROJECT_ID`) and the service account and workload identity provider that follow from it.  `--name` is the name of the identity if it was set up under another one, and the GCP `--pool` and `--provider` if they are not `github`.  If any is missing nothing is set.
The identifiers of the account (the role ARN, the client, tenant and subscription ids, the Key Vault, the project and the service account) are set as
**secrets of the environment**, only the region as a variable: GitHub masks the value of a secret from the first step of a job, in a log that is public,
whereas a variable is printed as it is, eg in the `env:` block of each step.  The workflow reads a secret before a variable of the same name, so after running `create` again
of an environment that had variables, delete those once a run has used the secrets (`gh variable delete <name> --env smoke-tests-<cloud>`).

To delete it, with its variables and secrets, when no longer required:
```shell
afon-cli.sh github-cloud-env delete <cloud> --force
```
`create` can be repeated.  It takes the values from the active config, and from what the cloud CLI knows (the Azure subscription, the GCP project number): the account identifiers
are set as secrets of the environment and the region as a variable; if a required one is missing it fails without setting anything.  It prints the subject that the cloud-side trust must be limited to.
The environment allows deployments from `main` only (`--branch` to change it), and admins cannot bypass that; `--reviewer <login>` (repeatable) requires approval of each run.
`--config-env <env> --config-tag <tag>` also set the repository variables `SMOKE_TESTS_CONFIG_ENV` and `SMOKE_TESTS_CONFIG_TAG` that the workflows use when triggered by a build.
`--dry-run` reports what would be done, and `show <cloud>` reports the environment, its branch rules, reviewers and variables.  Nothing is done in the cloud: that is [github-cloud-identity](#cloud-side).

The called workflow already has `id-token: write`; the callers grant it too, as a called workflow cannot have more permissions than its caller.

### If the login fails

The error is nearly always that the token's `sub` does not match the rule.  Check the environment name of the job (`smoke-tests-<cloud>`, case-sensitive) against the subject in the rule, and that `<owner>/afon` is the repo's exact owner and name.
- AWS: `Not authorized to perform sts:AssumeRoleWithWebIdentity`: the `sub` or `aud` condition of the trust policy, or the provider missing from the account.
- Azure: `AADSTS70021: No matching federated identity record found`: the subject, issuer or audience of the federated credential.
- GCP: `unauthorized_client` or a rejected attribute condition: the `--attribute-condition` of the provider, or the `principal://` member of the binding.
- `Unable to get ACTIONS_ID_TOKEN_REQUEST_URL`: the job lacks `id-token: write`, eg the caller workflow does not grant it.
- Azure, late in a run: `AADSTS700024: Client assertion is not within its valid time range`.  The token that `azure/login` logs in with lasts 5 minutes, and
  `az` presents it again to get the token of a resource it has not been asked for yet, eg the storage of the cdktf store at the end of the run.  So `cdktf-store`
  logs in again with a fresh token (`azure-login-oidc`) before it uses the store, in a job of GitHub Actions (it needs the job env variables `AZURE_OIDC_*`).  If another
  step fails the same way late in a run, run `afon-cli.sh azure-login-oidc` before it.  AWS (a 2-hour session) and GCP (the credentials file reads a new
  token from the runner) are not affected.

To see the claims of a job's token, print them in a throw-away workflow step (`curl -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=<aud>"`,
then decode the JWT payload): the token is not a secret to the job that holds it, but do not leave it in a log.

## Slack notifications

The [Acceptance Tests (Linux)](../.github/workflows/acceptance-tests-linux.yml) and [smoke tests](#smoke-tests-workflows) workflows post to the Slack channel `#afon-ci` on `emryshealth.slack.com` when their job fails, with the workflow, the branch, the commit, what triggered the run and a link to it.  When a job then passes again, they post that it has recovered.  Other successful runs post nothing.

A job has recovered when the run before it failed: the previous attempt of the same run, when it is re-run, else the
latest earlier run of the same workflow on the same branch, passing over skipped and cancelled runs.  The notifications are posted by
[slack.sh](../scripts/github/slack.sh), which reads the earlier runs with the job's own token, hence the
`actions: read` permission of the workflows.

The channel is the one the Slack incoming webhook was created for, not something set in the workflows.  The webhook
URL is held in the repository secret `SLACK_WEBHOOK_URL`.  Without it, the workflows only warn that no notification
was sent, and do not fail on that account.  Likewise if Slack or the GitHub API cannot be reached.

To set up the webhook, or to replace it:

1. Go to https://api.slack.com/apps, **Create New App** > **From scratch**, name it eg `afon CI` and pick the
   `emryshealth` workspace.
2. In **Incoming Webhooks**, switch them on, then **Add New Webhook to Workspace** and choose `#afon-ci`.  The
   workspace may need an admin to approve the app.
3. Copy the webhook URL and store it as the repository secret, which prompts for its value so that it is not left in
   the shell history:

   ```bash
   gh secret set SLACK_WEBHOOK_URL --repo emrys-health/afon
   ```

**NOTE**: anyone with the webhook URL can post to the channel, so keep it in the secret only.  To post to another
channel, create a webhook for that channel and replace the secret.

GitHub itself also notifies the user who started a run when it fails, ie who pushed the commit or ran the workflow
by hand, under **Settings** > **Notifications** > **System** > **Actions** of their GitHub account.  It does not
notify anyone else, hence the Slack channel.
