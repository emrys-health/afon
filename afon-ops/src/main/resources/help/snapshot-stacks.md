snapshot-stacks

Snapshots the plans that were synthesised into the stacks dir, to compare them, with compare-stacks, with those
of another time, eg before and after a refactoring.  Copies to <dir>/<stack-id>/cdk.tf.json the plan of each
stack, or of those given, that was synthesised, eg by validate-stack.  Copies nothing but those files.

Usage:
  afon-cli.sh snapshot-stacks <dir> [<stack-id>...]
