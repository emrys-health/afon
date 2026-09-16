source $AFON_ROOT/scripts/config/afon-source.sh aws

usage(){
  echo "Usage: $0 [all|keycloak|jupyter|polaris]"
  echo "Requests a new ACM certificate (DNS validation) for the given stack's domain,"
  echo "creates the validation CNAME in its Route53 hosted zone, and waits for ACM"
  echo "to issue it. Domain and zone come from afon.properties/afon-aws.properties,"
  echo "same as the CDK stacks themselves use. Defaults to 'all'."
  echo ""
  echo "Prints each new certificate's ARN - set it as the matching AWS_*_CERT_ARN"
  echo "property in afon-aws.properties."
}

NAME="${1:-all}"

# Issues one certificate: request, wait for the validation record, upsert its CNAME, then
# block until ACM reports it issued. Exits (returning to the caller) rather than aborting the
# whole script on failure, so 'all' can keep going and report the other domains' results too.
issue_cert() {
  local domain="$1" dns_zone="$2" property="$3"

  if [ -z "$domain" ] || [ -z "$dns_zone" ]; then
    echo "- Domain/DNS zone for $property are blank - check afon.properties/afon-aws.properties"
    return 1
  fi

  echo "- Requesting ACM certificate for $domain (DNS validation) in $AWS_REGION"
  local cert_arn
  cert_arn=$(aws acm request-certificate \
    --domain-name "$domain" \
    --validation-method DNS \
    --region "$AWS_REGION" \
    --query CertificateArn --output text) || return 1
  echo "- Requested $cert_arn"

  # ACM populates the validation record's details asynchronously after request-certificate
  # returns, typically within a few seconds - poll rather than assuming it's there immediately.
  echo "- Waiting for the validation record details..."
  local record_name=""
  for i in $(seq 1 12); do
    record_name=$(aws acm describe-certificate --certificate-arn "$cert_arn" --region "$AWS_REGION" \
      --query 'Certificate.DomainValidationOptions[0].ResourceRecord.Name' --output text 2>/dev/null)
    [ -n "$record_name" ] && [ "$record_name" != "None" ] && break
    sleep 5
  done
  if [ -z "$record_name" ] || [ "$record_name" = "None" ]; then
    echo "- Validation record details did not appear in time for $cert_arn"
    return 1
  fi
  local record_value
  record_value=$(aws acm describe-certificate --certificate-arn "$cert_arn" --region "$AWS_REGION" \
    --query 'Certificate.DomainValidationOptions[0].ResourceRecord.Value' --output text)

  echo "- Creating validation CNAME $record_name in zone $dns_zone"
  aws route53 change-resource-record-sets --hosted-zone-id "$dns_zone" --change-batch "{
    \"Changes\": [{
      \"Action\": \"UPSERT\",
      \"ResourceRecordSet\": {
        \"Name\": \"$record_name\",
        \"Type\": \"CNAME\",
        \"TTL\": 300,
        \"ResourceRecords\": [{\"Value\": \"$record_value\"}]
      }
    }]
  }" || return 1

  # This is what timed out last time - the CNAME above was never created (or never propagated)
  # before ACM gave up. The wait below is what surfaces that early instead of leaving a
  # VALIDATION_TIMED_OUT (terminal - needs a fresh request-certificate) cert wired into config.
  echo "- Waiting for $domain to validate and the certificate to be issued (can take a few minutes)..."
  if ! aws acm wait certificate-validated --certificate-arn "$cert_arn" --region "$AWS_REGION"; then
    echo "- Certificate did not validate in time. Check DNS propagation of $record_name, then either"
    echo "  re-run this script (a fresh request; the old one may still validate on its own) or inspect:"
    echo "    aws acm describe-certificate --certificate-arn $cert_arn --region $AWS_REGION"
    return 1
  fi

  echo "- $property issued: $cert_arn"
  RESULTS+=("$property=$cert_arn")
}

RESULTS=()
FAILED=()

case "$NAME" in
  all)
    issue_cert "$OIDC_DOMAIN_NAME"        "$AWS_KEYCLOAK_DNS_ZONE" "AWS_KEYCLOAK_CERT_ARN" || FAILED+=("AWS_KEYCLOAK_CERT_ARN")
    issue_cert "$AWS_JUPYTER_DOMAIN_NAME" "$AWS_JUPYTER_DNS_ZONE"  "AWS_JUPYTER_CERT_ARN"  || FAILED+=("AWS_JUPYTER_CERT_ARN")
    issue_cert "$AWS_POLARIS_DOMAIN_NAME" "$AWS_POLARIS_DNS_ZONE"  "AWS_POLARIS_CERT_ARN"  || FAILED+=("AWS_POLARIS_CERT_ARN")
    ;;
  keycloak) issue_cert "$OIDC_DOMAIN_NAME"        "$AWS_KEYCLOAK_DNS_ZONE" "AWS_KEYCLOAK_CERT_ARN" || FAILED+=("AWS_KEYCLOAK_CERT_ARN") ;;
  jupyter)  issue_cert "$AWS_JUPYTER_DOMAIN_NAME" "$AWS_JUPYTER_DNS_ZONE"  "AWS_JUPYTER_CERT_ARN"  || FAILED+=("AWS_JUPYTER_CERT_ARN") ;;
  polaris)  issue_cert "$AWS_POLARIS_DOMAIN_NAME" "$AWS_POLARIS_DNS_ZONE"  "AWS_POLARIS_CERT_ARN"  || FAILED+=("AWS_POLARIS_CERT_ARN") ;;
  *) usage; exit 1 ;;
esac

echo ""
if [ ${#RESULTS[@]} -gt 0 ]; then
  echo "Set these in afon-aws.properties:"
  for result in "${RESULTS[@]}"; do
    echo "  $result"
  done
fi
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Failed: ${FAILED[*]}"
  exit 1
fi
