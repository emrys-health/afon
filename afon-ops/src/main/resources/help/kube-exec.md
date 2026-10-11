kube-exec

Runs a command in a pod, like kubectl exec, and exits with the remote command's exit code.

Usage:
  afon-cli.sh kube-exec [-n <namespace>] [-c <container>] [-t <timeoutSeconds>] <pod> -- <command> [<args>...]

For example:
  afon-cli.sh kube-exec spark-connector-0-driver -- curl --connect-timeout 3 https://example.com
