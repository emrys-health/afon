# Shared by smoke/run-regression-suite.sh and kube/polaris-whitelist.sh, which source it, to read and decide the restriction of the
# Polaris ingress of a cluster, so that both do it the same way.  Not run on its own.
#
# The Polaris ingress is restricted to the CIDRs of AWS_POLARIS_ALLOWED_CIDRS or AZURE_POLARIS_ALLOWED_CIDRS, as the deploy does, plus
# the cluster's own addresses, so that the pods calling Polaris by its public domain name are not locked out: its egress IP on AWS, or
# its outbound IP and pod CIDR on Azure.  It is the annotation of the ingress: alb.ingress.kubernetes.io/inbound-cidrs on AWS, and
# nginx.ingress.kubernetes.io/whitelist-source-range on Azure, which only works with externalTrafficPolicy Local on the ingress
# controller's service.
#
# The caller sets CLOUD, aws or azure, and may define banner and warning, to colour them as it likes.  It may set
# POLARIS_ALLOWED_CIDRS, even to nothing, to have those CIDRs instead of the configured property, plus the cluster's own addresses.

# Defined only if the caller has not, which is tested as a function, not a command: some systems have a banner command, that prints
# a giant text.
declare -F banner >/dev/null || banner() { echo "=== $*"; }
declare -F warning >/dev/null || warning() { echo "WARNING: $*" >&2; }

# Prints the kubectl context of the cluster: CONTEXT, if the caller sets it, else the current one.
polaris_context() {
  echo "${CONTEXT:-$(kubectl config current-context 2>/dev/null || true)}"
}

# The config dir that aws_property and azure_property read the properties from.
AFON_CONFIG_DIR="${AFON_HOME:-$HOME/.afon}/config"

# The pod CIDR of an AKS overlay cluster, as AzureCloudProvider.POD_CIDR
AZURE_POD_CIDR="10.244.0.0/16"

# Prints property $1 of afon-aws.properties, without whitespace, or nothing if it is not set.
aws_property() {
  grep -h "^$1=" "$AFON_CONFIG_DIR/afon-aws.properties" 2>/dev/null | tail -1 | cut -d= -f2- \
    | tr -d '[:space:]' || true
}

# Prints the public IP of the NAT gateway of the cluster of the current kubectl context, the address all its
# outbound traffic comes from, or nothing if it cannot be found, eg if the context is not an EKS cluster's.
cluster_egress_ip() {
  local context region cluster ip
  context=$(polaris_context)
  # As written by 'aws eks update-kubeconfig': arn:aws:eks:<region>:<account>:cluster/<cluster>
  case "$context" in
    arn:aws:eks:*:cluster/*) ;;
    *) return 0 ;;
  esac
  region=$(echo "$context" | cut -d: -f4)
  cluster=$(echo "$context" | cut -d/ -f2)
  # The NAT gateway is named after the cluster, see AwsComponentNames.getNatGatewayName.
  ip=$(aws ec2 describe-nat-gateways --region "$region" \
         --filter "Name=tag:Name,Values=cdktf-eks-nat-$cluster" Name=state,Values=available \
         --query 'NatGateways[0].NatGatewayAddresses[0].PublicIp' --output text 2>/dev/null || true)
  if [ -n "$ip" ] && [ "$ip" != "None" ]; then
    echo "$ip"
  fi
}

# Prints property $1 of afon-azure.properties, without whitespace, or nothing if it is not set.
azure_property() {
  grep -h "^$1=" "$AFON_CONFIG_DIR/afon-azure.properties" 2>/dev/null | tail -1 | cut -d= -f2- \
    | tr -d '[:space:]' || true
}

# Prints the outbound public IP of the cluster of the current kubectl context, the address all its outbound traffic comes
# from, ie that of the first of the effective outbound IPs of its load balancer, or nothing if it cannot be found, eg if
# the context is not an AKS cluster's.
azure_cluster_outbound_ip() {
  local cluster group ip_id ip
  cluster=$(polaris_context)
  [ -n "$cluster" ] || return 0
  group=$(az aks list --query "[?name=='$cluster'].resourceGroup | [0]" --output tsv 2>/dev/null || true)
  [ -n "$group" ] || return 0
  ip_id=$(az aks show --name "$cluster" --resource-group "$group" \
            --query 'networkProfile.loadBalancerProfile.effectiveOutboundIPs[0].id' --output tsv 2>/dev/null || true)
  [ -n "$ip_id" ] && [ "$ip_id" != "None" ] || return 0
  ip=$(az network public-ip show --ids "$ip_id" --query ipAddress --output tsv 2>/dev/null || true)
  if [ -n "$ip" ] && [ "$ip" != "None" ]; then
    echo "$ip"
  fi
}

# Prints the annotation of the Polaris ingress that restricts who can call it, on the $CLOUD.
polaris_annotation() {
  case "$CLOUD" in
    aws) echo "alb.ingress.kubernetes.io/inbound-cidrs" ;;
    azure) echo "nginx.ingress.kubernetes.io/whitelist-source-range" ;;
  esac
}

# Prints the state of annotation $1 of the Polaris ingress of kubectl context $2, as it is in the cluster: "present:<value>"
# if it is set, even to the empty string, "absent" if it is not, "missing" if there is no Polaris ingress.
ingress_annotation() {
  local out
  if ! out=$(kubectl --context "$2" get ingress polaris-ingress -n default \
               -o go-template="{{range \$k, \$v := .metadata.annotations}}{{if eq \$k \"$1\"}}present:{{\$v}}{{end}}{{end}}" \
               2>/dev/null); then
    echo "missing"
    return 0
  fi
  echo "${out:-absent}"
}

# Prints the nginx ingress controller service of kubectl context $1, as service/<name>, or nothing if it has none.
controller_service() {
  kubectl --context "$1" get service -n ingress-nginx -l app.kubernetes.io/component=controller -o name 2>/dev/null \
    | grep -v admission | head -1 || true
}

# Prints the CIDRs that the Polaris ingress of the current kubectl context can be restricted to, for the network smoke
# test to pass: those of AWS_POLARIS_ALLOWED_CIDRS or AZURE_POLARIS_ALLOWED_CIDRS, as the deploy applies, plus the
# cluster's own addresses, so that its pods calling Polaris by its public domain name are not locked out.  Prints nothing
# if the cluster's address cannot be found on AWS.  Warns, to stderr, about what it could not find.
restriction_cidrs() {
  case "$CLOUD" in
    aws) aws_restriction_cidrs ;;
    azure) azure_restriction_cidrs ;;
  esac
}

# On AWS the cluster's own address is its egress IP, that of the NAT gateway, as the AWS_EGRESS_IP of afon-aws.properties
# may be stale, eg if the stack was deployed without it, or by default AWS_EGRESS_IP if the NAT gateway cannot be found.
aws_restriction_cidrs() {
  local configured_ip egress_ip cidrs cidr configured egress_source="the cluster's NAT gateway, with aws ec2"
  configured_ip=$(aws_property AWS_EGRESS_IP)
  configured="${POLARIS_ALLOWED_CIDRS-$(aws_property AWS_POLARIS_ALLOWED_CIDRS)}"
  egress_ip=$(cluster_egress_ip)
  if [ -z "$egress_ip" ]; then
    egress_source="AWS_EGRESS_IP in $AFON_CONFIG_DIR/afon-aws.properties, as no NAT gateway was found"
    warning "no NAT gateway found for kubectl context $(polaris_context):" \
            "using AWS_EGRESS_IP '$configured_ip'"
    egress_ip="$configured_ip"
  elif [ -n "$configured_ip" ] && [ "$configured_ip" != "$egress_ip" ]; then
    warning "the cluster's egress IP $egress_ip is not AWS_EGRESS_IP $configured_ip: using $egress_ip." \
            "Update AWS_EGRESS_IP for the stack to keep its egress IP across rebuilds"
  fi
  if [ -z "$egress_ip" ]; then
    warning "no egress IP for the cluster"
    return 0
  fi
  cidrs=
  for cidr in $(echo "$configured" | tr ',' ' '); do
    if [ "$cidr" != "$egress_ip/32" ]; then
      cidrs="$cidrs$cidr,"
    fi
  done
  # to stderr, as the CIDRs are what this prints to stdout
  banner "Polaris ingress CIDRs, from:" >&2
  if [ "${POLARIS_ALLOWED_CIDRS+set}" = "set" ]; then
    configured_from="given"
  else
    configured_from="AWS_POLARIS_ALLOWED_CIDRS in $AFON_CONFIG_DIR/afon-aws.properties"
  fi
  echo "  configured: ${configured:-none} ($configured_from)" >&2
  echo "  egress IP: $egress_ip ($egress_source)" >&2
  echo "$cidrs$egress_ip/32"
}

# On Azure the cluster's own addresses are its pod CIDR and its outbound IP.
azure_restriction_cidrs() {
  local outbound_ip configured_ip egress_ip egress_source cidrs cidr configured
  outbound_ip=$(azure_cluster_outbound_ip)
  configured_ip=$(azure_property AZURE_EGRESS_IP)
  egress_ip="$outbound_ip"
  egress_source="the first effective outbound IP of the cluster's load balancer, with az"
  if [ -z "$egress_ip" ]; then
    # as on AWS, the cluster's own address is preferred, as the configured one may be stale, eg if the stack was deployed
    # without it, but az need not be logged in
    egress_ip="$configured_ip"
    egress_source="AZURE_EGRESS_IP in $AFON_CONFIG_DIR/afon-azure.properties, as no outbound IP was found with az"
  elif [ -n "$configured_ip" ] && [ "$configured_ip" != "$egress_ip" ]; then
    warning "the cluster's outbound IP $egress_ip is not AZURE_EGRESS_IP $configured_ip: using $egress_ip." \
            "Redeploy the stack to pin its outbound IP to AZURE_EGRESS_IP"
  fi
  configured="${POLARIS_ALLOWED_CIDRS-$(azure_property AZURE_POLARIS_ALLOWED_CIDRS)}"
  cidrs=
  for cidr in $(echo "$configured" | tr ',' ' '); do
    case ",$cidrs," in
      *",$cidr,"*) ;;
      *) cidrs="$cidrs$cidr," ;;
    esac
  done
  if [ -z "$egress_ip" ]; then
    warning "no outbound IP found for kubectl context $(polaris_context):" \
            "not including it"
  else
    case ",$cidrs," in
      *",$egress_ip/32,"*) ;;
      *) cidrs="$cidrs$egress_ip/32," ;;
    esac
  fi
  # to stderr, as the CIDRs are what this prints to stdout
  banner "Polaris ingress CIDRs, from:" >&2
  if [ "${POLARIS_ALLOWED_CIDRS+set}" = "set" ]; then
    configured_from="given"
  else
    configured_from="AZURE_POLARIS_ALLOWED_CIDRS in $AFON_CONFIG_DIR/afon-azure.properties"
  fi
  echo "  configured: ${configured:-none} ($configured_from)" >&2
  echo "  outbound IP: ${egress_ip:-not found} ($egress_source)" >&2
  echo "  pod CIDR: $AZURE_POD_CIDR (AZURE_POD_CIDR of scripts/kube/polaris-whitelist-source.sh, as AzureCloudProvider.POD_CIDR)" >&2
  echo "$cidrs$AZURE_POD_CIDR"
}

# Prints the kubectl commands that restrict the Polaris ingress of kubectl context $1 to the CIDRs $2, for the $CLOUD.
# On Azure the ingress controller must also keep the callers' IPs, which its load balancer otherwise hides.
print_restrict_commands() {
  local context="$1" cidrs="$2" controller
  case "$CLOUD" in
    aws)
      echo "  kubectl --context $context annotate ingress polaris-ingress -n default --overwrite" \
           "alb.ingress.kubernetes.io/inbound-cidrs=$cidrs" ;;
    azure)
      controller=$(controller_service "$context")
      echo "  kubectl --context $context patch ${controller:-<the ingress-nginx controller service>} -n ingress-nginx" \
           "--type merge -p '{\"spec\":{\"externalTrafficPolicy\":\"Local\"}}'"
      echo "  kubectl --context $context annotate ingress polaris-ingress -n default --overwrite" \
           "nginx.ingress.kubernetes.io/whitelist-source-range=$cidrs" ;;
  esac
}
