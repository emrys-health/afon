validate-stack

Validates the config of a stack by synthesising its plan from the config, without deploying it.
It also checks that the plan is the same in each JVM: the stack is synthesised in three JVMs of their own, one after
the other (--runs <n> sets how many, 3 by default; --runs 1 synthesises once, in this JVM, and checks no more),
and the command fails, with the paths that differ (not their values, which are config), if the plans differ.
Else tofu would see a change, and replace what holds it, on every deploy, eg when a plan depends on the iteration
order of an immutable map.

Usage:
  afon-cli.sh validate-stack <stack-id> [--runs <n>]

<stack-id> is <cloud>-<stack-type>, eg azure-polaris.  Without a stack id, lists the stacks.
