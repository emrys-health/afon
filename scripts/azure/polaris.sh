# Optionally given the type of the stack serving Polaris, eg: afon-cli.sh azure-polaris [full]
# Without it, the stack is found among the running stacks of the types serving Polaris, by their AKS clusters
# <stack-type>-<CLUSTER_NAME>: as they all hold the DNS record of AZURE_POLARIS_DOMAIN_NAME, only one runs at a time.
# These are azure-polaris and azure-full.
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/kube/polaris-source.sh
source $AFON_ROOT/scripts/azure/status-source.sh

# Reports whether Polaris is up and serving on this env's Azure stack: login, AKS network, AKS cluster,
# Polaris pod, ingress external IP, TLS secret, then real HTTPS requests, anonymous and as the Polaris admin.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

status_require_domain AZURE_POLARIS_DOMAIN_NAME "$AZURE_POLARIS_DOMAIN_NAME"
status_require az kubectl kubelogin curl

status_require_login Azure azure-login az account get-access-token --output none

azure_status_find_stack "$STACK_TYPE_ARG" polaris full

echo "- Checking Polaris for cluster '${STATUS_CLUSTER_NAME:-none}' at $STATUS_DOMAIN"

azure_status_check_cluster
status_check_nodes
status_check_pod app=polaris
status_check_ingress polaris-ingress ip "external IP"
status_check_tls polaris-tls
polaris_check_https
polaris_check_authenticated azure
status_report
