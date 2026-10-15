validate-stack

Validates the config of a stack by synthesising its plan from the config, without deploying it.
It first prints the role of the stack, if it has one, and its components, in the order they are deployed, as defined in the
stacks.yaml it is loaded from.
It also checks that the plan is the same in each JVM: the stack is synthesised in three JVMs of their own, one after
the other (--runs <n> sets how many, 3 by default; --runs 1 synthesises once, in this JVM, and checks no more),
and the command fails, with the paths that differ (not their values, which are config), if the plans differ.
Else tofu would see a change, and replace what holds it, on every deploy, eg when a plan depends on the iteration
order of an immutable map.

Usage:
  afon-cli.sh validate-stack <stack-id> [--runs <n>]
  afon-cli.sh validate-stack --export-stacks

<stack-id> is <cloud>-<stack-type>, eg azure-polaris.  Without a stack id, lists the stacks.

The stacks are those of the stacks.yaml in the config dir, if there is one, else of the one bundled in the jar.  If the file is
not valid, the command fails, naming the file and the problem.

--export-stacks writes the bundled stacks.yaml to the config dir, to edit it: the file in the config dir replaces the bundled one
entirely.  It never overwrites a file, so remove it first to export the bundled stacks again.
