manage-stack

Manages a stack by running the steps of an action on it, runs a scenario on a stack that is set up, or runs a flow:
a named, ordered list of actions and scenarios.

Usage:
  afon-cli.sh manage-stack <action> <stack-id> [<step>] [--replace]
  afon-cli.sh manage-stack run <stack-id> [<scenario> [<step>]]
  afon-cli.sh manage-stack run [<flow> [<item>:<step>]] [--replace] [--dry-run]
  afon-cli.sh manage-stack run --export-flows

<action> is one of:
  deploy   deploys the stack, waiting for DNS to propagate
  setup    also configures it, eg the OIDC users and the Polaris catalogs
  delete   deletes the stack

<stack-id> is <cloud>-<stack-type>, eg aws-keycloak, or <cloud>-all, eg aws-all, to delete every stack of the cloud.
  Not every action is supported by every stack: run 'afon-cli.sh manage-stack' without args to list the stacks
  with their actions, and the scenarios of each.

<step>, if given, is the step from which to resume a failed run, as listed in the docs of the action.

--replace lets deploy and setup delete the other stacks conflicting with the stack, else they fail, naming them,
  before deleting any.  Stacks run side by side unless they conflict.

run <stack-id> runs a scenario on a stack that is set up, eg 'run aws-snowflake reading-azure'; without a scenario
  it lists those of the stack.
run <flow> runs a flow, stopping at the first failure, which reports the <item>:<step> to resume from; without
  args it lists the flows and the stacks with scenarios.  --dry-run lists the steps of a flow without running them.
  The flows are those of the stack-flows.yaml in the config dir, if there is one, else of the one bundled in the jar.
  --export-flows writes the bundled one to the config dir, to edit it: it replaces the bundled one entirely, and is
  never overwritten, so remove it first to export the bundled flows again.

Add -h to an action on a stack, or to a flow, to show its generated doc with the steps and how to resume, eg:
  afon-cli.sh manage-stack setup aws-full -h
  afon-cli.sh manage-stack run cross-cloud-azure -h
