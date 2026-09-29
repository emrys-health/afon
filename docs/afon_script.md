## afon.sh commands

The `afon.sh` script at the top-level of the source tree acts as a front-end for all the functionality, including the build:

```shell
./afon.sh -h
```

For access to the functionality outside the source tree, refer to [Afon CLI](cli/afon_cli.md).

### Options in long form and short form

The `afon.sh` accepts options in long form and, for the most common ones, also in short form.

See usage for details:
```shell
./afon.sh -h
```
For brevity, in the docs, we'll be mostly using the short forms where available.

### Ops commands

Every executable ops class `<Name>Main` (in module `afon-ops`) can be run using its kebab-case name as the action,
as a shorthand for the `exec` action:

```shell
./afon.sh -a manage-stack deploy aws-full   # same as: ./afon.sh -a exec -ec ops.ManageStackMain -ea "deploy aws-full"
./afon.sh -a manage-polaris-catalogs -ea "create aws"
```

The exec args of an ops command can also be passed as positional args, without the `--exec-args` flag, like in the CLI:

```shell
./afon.sh -a manage-polaris-catalogs create aws     # same as: -ea "create aws"
./afon.sh -a manage-polaris-catalogs -- --some-flag # use '--' first if an arg starts with '-'
```

Positional args are only accepted by the ops commands, and not together with `--exec-args`.

The class is looked up in the source tree, so it is a pure `afon.sh` mapping with no dependency on the CLI.
`--exec-args` and the other `--exec-*` options apply as usual.  If a script `scripts/<dir>/<name>.sh` exists for the
same action name, the script takes precedence over the ops class.  The list of ops commands is shown in the usage:
```shell
./afon.sh -h
```

