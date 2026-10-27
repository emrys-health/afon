## Stack AWS workflow

The [Stack AWS (Linux)](../.github/workflows/aws-stack-linux.yml) workflow runs `afon.sh` on the self-hosted Linux runner — ie
`./afon.sh -a manage-stack <action> <stack-id> [<step>]` (see [manage a stack](stacks.md#manage-a-stack)).
It runs one action on one stack; to set up several stacks and run scenarios in order, use a flow, see
[run a flow of stacks](stacks.md#run-a-flow-of-stacks).

Run it from the Actions tab: it is `workflow_dispatch` only, with an `action` dropdown
(`deploy`/`setup`/`delete`), a `stack_id` dropdown (`aws-full`/`aws-keycloak`/`aws-polaris`/`aws-all`, defaulting to
`aws-full`) and a `from_step` to resume from (blank runs every step, starting with the teardown).
`deploy` and `setup` tear down the running stacks that conflict with the stack, including itself, before rebuilding it
(the workflow runs `manage-stack` with `--replace`, so it replaces the other conflicting stacks too),
and `setup` also configures it; `delete` tears it down and leaves it down — a later `deploy` or `setup` run is what
brings it back.  `aws-keycloak` and `aws-polaris` run side by side, `aws-polaris` using the KeyCloak of `aws-keycloak`,
while `aws-full` conflicts with both, see [stacks side by side](stacks.md#stacks-side-by-side).
`delete` of `aws-all` tears down every AWS stack that is up; `aws-all` supports no other action.

All actions run on the *same* self-hosted runner (see the `runs-on` note in the workflow), because
all need the tofu state described below — `delete` just as much as `deploy`, since that state is
the only record of what there is to tear down.

Pre-requisites, on top of what the acceptance workflow needs:

- **Runner tooling**: the `aws` CLI, `tofu` and `kubectl` on the runner's PATH. Install them with
  `ansible-playbook runner-setup.yml -e install_cloud_tools=true` (see [runners](../scripts/github/runners.md)).
- **Config secrets**: the same base64 secrets the acceptance workflow uses, published with
  `afon-cli.sh github-secrets push`.  To run against a config env other than the one behind the
  repository secrets, publish it with `afon-cli.sh github-secrets push --env <env>` and give the workflow's `config_env` input.
  Each env then gets its own tofu state, in `$AFON_CDKTF-<env>` on the runner.
- **AWS admin credentials**: the repository secrets `AWS_DEPLOY_ACCESS_KEY_ID` and
  `AWS_DEPLOY_SECRET_ACCESS_KEY`. These are the user admin credentials tofu and the CLI run as,
  **NOTE** the Polaris service account credentials in `secret-aws.properties` (see
  [configuration](configuration.md)). Leave them unset to use the credentials the runner already
  has, from an EC2 instance profile or its `~/.aws/credentials`.
- **Tofu state**: kept on the runner in `$AFON_CDKTF/<owner>/<repo>/aws`.

**NOTE**: the `$AFON_CDKTF` points to `/data/afon/cdktf` and lives outside the GitHub workspace,
which is wiped out at each checkout.   On the runner, the `$AFON_HOME` points to the workspace.

The region comes from `AWS_REGION` in `afon-aws.properties`, and the run's log is uploaded as the
`afon-stack-aws-logs-<run id>` artifact.

## Slack notifications

The [Acceptance Tests (Linux)](../.github/workflows/acceptance-tests-linux.yml),
[AWS Stack (Linux)](../.github/workflows/aws-stack-linux.yml) and [CLI (Linux)](../.github/workflows/cli-linux.yml)
workflows post to the Slack channel `#afon-ci` on `emryshealth.slack.com` when their job fails, with the workflow, the
branch, the commit, what triggered the run and a link to it.  When a job then passes again, they post that it has
recovered.  Other successful runs post nothing.

A job has recovered when the run before it failed: the previous attempt of the same run, when it is re-run, else the
latest earlier run of the same workflow on the same branch, passing over skipped and cancelled runs.  For the
AWS Stack workflow, that earlier run may be of another action or stack.  The notifications are posted by
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
