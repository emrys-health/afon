#!/usr/bin/env bash
# Sourced by the scripts that write the kubeconfig of a stack.  Not run on its own.
# Every stack has one kubeconfig, $AFON_CDKTF/kube/<stack-id>.config, the only place its credentials are written: the
# default ~/.kube/config is never written, as concurrent stacks would switch each other's current context.
# kubeconfig_path <stack-id>: prints the path of the kubeconfig of the stack
kubeconfig_path() {
  echo "${AFON_CDKTF:-${CDKTF_DIR:-$HOME/.afon/cdktf}}/kube/$1.config"
}

# kubeconfig_current <stack-id>: points <kube dir>/current.config at the kubeconfig of the stack, for the convenience of
# users: export KUBECONFIG=<kube dir>/current.config.  Nothing depends on it.
kubeconfig_current() {
  ln -sfn "$(kubeconfig_path "$1")" "$(dirname "$(kubeconfig_path "$1")")/current.config"
}
