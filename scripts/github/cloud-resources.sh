#!/usr/bin/env bash
# Searches the TESTING cloud account (AWS account, Azure subscription or GCP project) for the resources of a smoke tests stack that were left behind, eg by a job that
# was killed during the apply of the stack, whose state was then not stored (see docs/github_workflows.md, section If the job is stopped).
# It only reports: nothing is deleted.  The resources are those that the stack names after its cluster, <stack-type>-<cluster-name>, so a
# stack that is up and running is listed too: use --older-than to leave out those of a run in progress.
#
# github-cloud-resources <aws|azure|gcp> [--stack-type <type>] [--older-than <hours>]
#
# Run it after <cloud>-login, with read access to the testing cloud account.  What it lists, with the age where the cloud tells it:
#   aws    the EKS clusters, VPCs, NAT gateways, load balancers and IAM roles named after the cluster, in the region AWS_REGION
#   azure  the resources named <stack-type>-<cluster-name>* in the resource group AZURE_RESOURCE_GROUP, and the AKS node resource group of the cluster
#   gcp    the GKE clusters, VPC networks, subnets, routers and addresses named after the cluster, in the project GCP_PROJECT_ID
# The retained volumes outlive the stacks by design and are not listed.  A resource whose age the cloud does not tell is always listed.
#
# Options: --stack-type (default full, as in the stack id <cloud>-full of the smoke tests), --older-than <hours> (default 3: leaves out
#          the resources created in the last hours).  The cluster name (CLUSTER_NAME) and the region, resource group or project are those of
#          the active config: to search another, switch the env (--env <env>).
#
# Delete what it lists by hand, or with the state of the last deploy if the cdktf store has it (cdktf-store pull current <stack-id>,
# tofu-init, tofu-destroy).  Exits with 0 if it found nothing, 1 if it found resources, 2 on an error.
set -uo pipefail

DEFAULT_OLDER_THAN=3
DOCS="docs/github_workflows.md#if-the-job-is-stopped"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} {aws|azure|gcp} [--stack-type <type>] [--older-than <hours>]"
  echo "stack-type:     the type of the stack id <cloud>-<type>.  Defaults to full."
  echo "older-than:     lists only the resources older than this many hours.  Defaults to $DEFAULT_OLDER_THAN."
  echo "The cluster name and the region, resource group or project are those of the active config."
  echo "Only reports, never deletes.  Exits with 0 if nothing is left, 1 if resources are.  See $DOCS."
}

die(){
  echo "$1" >&2
  exit 2
}

need(){
  command -v "$1" > /dev/null 2>&1 || die "Requires $1 on the PATH"
}

# Prints the value of a property of the active config (the config dir of AFON_HOME, which the env switched to), which may be set in terms
# of others.  Prints nothing if there is none.
config_property(){
  local PROPERTY="$1" CLOUD_NAME="$2" FILE
  (
    set +eu
    set -a
    for FILE in afon.properties "afon-$CLOUD_NAME.properties" ; do {
      [ -f "${AFON_HOME:-}/config/$FILE" ] && source "${AFON_HOME:-}/config/$FILE" > /dev/null 2>&1
    } done
    printf '%s' "${!PROPERTY:-}"
  )
}

# The lines 'kind<TAB>name<TAB>created' of the resources found, created being an ISO 8601 time or empty.
FOUND="$(mktemp)"
trap 'rm -f "$FOUND"' EXIT

add(){
  printf '%s\t%s\t%s\n' "$1" "$2" "${3:-}" >> "$FOUND"
}

# Adds the resources of the JSON array on stdin, whose items have a name and a created field.
add_json(){
  local KIND="$1"
  jq -r --arg kind "$KIND" '.[]? | [$kind, .name, (.created // "")] | @tsv' | while IFS=$'\t' read -r K N C; do add "$K" "$N" "$C"; done
}

# --- AWS ---------------------------------------------------------------------------------------------------------------------------

aws_search(){
  [ -n "$REGION" ] || die "No AWS_REGION in the active config"
  local R=(--region "$REGION" --output json)
  local ACCOUNT
  ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2> /dev/null)" || die "aws is not logged in"
  echo "AWS account $ACCOUNT, region $REGION"

  local NAME
  for NAME in $(aws eks list-clusters "${R[@]}" --query "clusters[?@=='$CLUSTER']" --output text 2> /dev/null); do
    aws eks describe-cluster --name "$NAME" "${R[@]}" --query 'cluster.{name: name, created: createdAt}' 2> /dev/null \
      | jq -s '.' | add_json "eks cluster"
  done
  aws ec2 describe-vpcs "${R[@]}" --filters "Name=tag:Name,Values=cdktf-eks-vpc-$CLUSTER" \
    --query 'Vpcs[].{name: VpcId}' 2> /dev/null | add_json "vpc"
  aws ec2 describe-nat-gateways "${R[@]}" --filter "Name=tag:Name,Values=cdktf-eks-nat-$CLUSTER" "Name=state,Values=pending,available" \
    --query 'NatGateways[].{name: NatGatewayId, created: CreateTime}' 2> /dev/null | add_json "nat gateway"
  aws elbv2 describe-load-balancers "${R[@]}" \
    --query "LoadBalancers[?contains(LoadBalancerName, 'k8s-')].{name: LoadBalancerName, created: CreatedTime, vpc: VpcId}" 2> /dev/null \
    | jq --argjson vpcs "$(aws ec2 describe-vpcs "${R[@]}" --filters "Name=tag:Name,Values=cdktf-eks-vpc-$CLUSTER" \
        --query 'Vpcs[].VpcId' 2> /dev/null || echo '[]')" '[.[]? | select(.vpc as $v | $vpcs | index($v))]' | add_json "load balancer"
  aws iam list-roles --query "Roles[?ends_with(RoleName, '-$CLUSTER') && (starts_with(RoleName, 'cdktf-eks-') \
    || starts_with(RoleName, 'AmazonEKSLoadBalancerController'))].{name: RoleName, created: CreateDate}" --output json 2> /dev/null \
    | add_json "iam role"
}

# --- Azure -------------------------------------------------------------------------------------------------------------------------

azure_search(){
  [ -n "$RESOURCE_GROUP" ] || die "No AZURE_RESOURCE_GROUP in the active config"
  local SUBSCRIPTION
  SUBSCRIPTION="$(az account show --query name -o tsv 2> /dev/null)" || die "az is not logged in"
  echo "Azure subscription $SUBSCRIPTION, resource group $RESOURCE_GROUP"

  az resource list --resource-group "$RESOURCE_GROUP" --query "[?starts_with(name, '$STACK_TYPE-$CLUSTER_NAME')].{name: name, kind: type, created: createdTime}" \
    -o json 2> /dev/null | jq '[.[]? | {name: (.kind + " " + .name), created}]' | add_json "resource"
  az group list --query "[?starts_with(name, 'MC_${RESOURCE_GROUP}_${STACK_TYPE}-${CLUSTER_NAME}')].{name: name}" -o json 2> /dev/null \
    | add_json "aks node resource group"
}

# --- GCP ---------------------------------------------------------------------------------------------------------------------------

gcp_search(){
  [ -n "$PROJECT_ID" ] || die "No GCP_PROJECT_ID in the active config"
  local P=(--project "$PROJECT_ID" --format json)
  echo "GCP project $PROJECT_ID"

  gcloud container clusters list "${P[@]}" --filter "name=$CLUSTER" 2> /dev/null \
    | jq '[.[]? | {name, created: .createTime}]' | add_json "gke cluster"
  gcloud compute networks list "${P[@]}" --filter "name=gke-private-vpc-$CLUSTER" 2> /dev/null \
    | jq '[.[]? | {name, created: .creationTimestamp}]' | add_json "vpc network"
  gcloud compute networks subnets list "${P[@]}" --filter "name=gke-private-subnet-$CLUSTER" 2> /dev/null \
    | jq '[.[]? | {name, created: .creationTimestamp}]' | add_json "subnet"
  gcloud compute routers list "${P[@]}" --filter "name=gke-router-$CLUSTER" 2> /dev/null \
    | jq '[.[]? | {name, created: .creationTimestamp}]' | add_json "router"
  gcloud compute addresses list "${P[@]}" --filter "name~$CLUSTER" 2> /dev/null \
    | jq '[.[]? | {name, created: .creationTimestamp}]' | add_json "address"
}

# --- Arguments ---------------------------------------------------------------------------------------------------------------------

if [ $# -eq 0 ] || [ "$1" == "-h" ] || [ "$1" == "--help" ]; then usage; exit 0; fi
CLOUD="$1"
shift
STACK_TYPE="full"
OLDER_THAN="$DEFAULT_OLDER_THAN"
while [ $# -gt 0 ]; do
  case "$1" in
    --stack-type) STACK_TYPE="${2:-}" ;;
    --older-than) OLDER_THAN="${2:-}" ;;
    *) usage >&2; die "Unknown option '$1'" ;;
  esac
  [ $# -ge 2 ] || die "Option $1 needs a value"
  shift 2
done

case "$OLDER_THAN" in ''|*[!0-9]*) die "--older-than must be a number of hours" ;; esac
need jq
case "$CLOUD" in
  aws) need aws; REGION="$(config_property AWS_REGION aws)" ;;
  azure) need az; RESOURCE_GROUP="$(config_property AZURE_RESOURCE_GROUP azure)" ;;
  gcp) need gcloud; PROJECT_ID="$(config_property GCP_PROJECT_ID gcp)" ;;
  *) usage >&2; die "Unknown cloud '$CLOUD': it must be aws, azure or gcp" ;;
esac
CLUSTER_NAME="$(config_property CLUSTER_NAME "$CLOUD")"
[ -n "$CLUSTER_NAME" ] || die "No CLUSTER_NAME in the active config"
CLUSTER="$STACK_TYPE-$CLUSTER_NAME"

echo "Searching for the resources of the stack $CLOUD-$STACK_TYPE, cluster $CLUSTER, older than $OLDER_THAN hours"
"${CLOUD}_search"

# --- Report ------------------------------------------------------------------------------------------------------------------------

# Keeps the resources older than the limit, or of unknown age; prints them oldest first.
REPORT="$(jq -R -r --argjson hours "$OLDER_THAN" --argjson now "$(date +%s)" '
  def epoch:
    sub("\\.[0-9]+"; "") as $s
    | if ($s | test("[+-][0-9]{2}:[0-9]{2}$"))
      then (($s[0:19] + "Z") | fromdateiso8601)
           - ((($s[-6:-5] + "1") | tonumber) * ((($s[-5:-3]) | tonumber) * 3600 + (($s[-2:]) | tonumber) * 60))
      else (($s | sub("Z$"; "") | .[0:19] + "Z") | fromdateiso8601) end;
  split("\t") as [$kind, $name, $created]
  | (if $created == "" then null else ($created | epoch) end) as $at
  | select($at == null or ($now - $at) >= $hours * 3600)
  | [($at // 0), $kind, $name, (if $at == null then "age unknown" else "\((($now - $at) / 3600 * 10 | floor) / 10) hours" end)]
  | @tsv' "$FOUND" | sort -n | cut -f2-)"

if [ -z "$REPORT" ]; then
  echo "Nothing left behind."
  exit 0
fi
echo
printf '%s\n' "$REPORT" | awk -F'\t' '{ printf "  %-24s %-60s %s\n", $1, $2, $3 }'
echo
echo "$(printf '%s\n' "$REPORT" | wc -l | tr -d ' ') resource(s) found.  Delete them by hand, or with the stored state: see $DOCS."
exit 1
