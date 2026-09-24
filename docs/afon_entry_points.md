# Afon Entry Points

Afon supports both CLI and non-CLI entry points. 
 
The [CLI](afon_cli.md) entry point is the `afon-cli.sh` script, which does not require access to the source code.

The [non-CLI](afon_script.md) entry point is the `afon.sh` script at the top of source code tree. 

The `afon.sh` script allows access to the same functionality of `afon-cli.sh` script, with the addition of the build 
functions. 

In particular, both entry points can execute the same commands.  The only caveat is that `afon.sh` requires the specification of the `-a` flag.  

So, eg the CLI commands:
```shell
afon-cli.sh setup-aws-full-stack 
afon-cli.sh azure-create -e <env>
afon-cli.sh validate-stack <stack> -e <env>
```
are equivalent to:
```shell
./afon.sh -a setup-aws-full-stack 
./afon.sh -a azure-create -e <env>
./afon.sh -a validate-stack <stack> -e <env>
```



