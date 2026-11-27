manage-stack compare

Compares the plans of the stacks in two dirs, each a snapshot made with manage-stack snapshot, or the stacks dir.
Lists the resources added and removed (which tofu creates and destroys; a renamed one is both), then the values
that changed, with the long ones reduced to their hash.

Usage:
  afon-cli.sh manage-stack compare <before-dir> <after-dir>

Exits with 1 if the plans differ.
