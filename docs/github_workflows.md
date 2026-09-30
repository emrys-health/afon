## Stack AWS workflow

The [Stack AWS (Linux)](../.github/workflows/aws-stack-linux.yml) workflow runs `afon.sh` on the self-hosted Linux runner — ie
`./afon.sh -a manage-stack <action> <stack-id> [<step>]` (see [manage a stack](stacks.md#manage-a-stack)).

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

