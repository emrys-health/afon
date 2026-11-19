#!/usr/bin/env bash
# Manages the identities that Amazon SES lets mail be sent to, and the account's access to it: the central identity's Keycloak sends its invitations
# through SES, which in its sandbox delivers only to verified identities, ie addresses, or domains, see docs/projects.md.  Needs the AWS CLI, logged in.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <action> [<args>] [--region <region>]"
  echo "where <action> is one of:"
  echo "  list                       the identities of the region, and whether each can send"
  echo "  create <identity>          makes an identity, an address (rob@example.org) or a domain (example.org): SES emails an address a link to click,"
  echo "                             and gives a domain the DKIM records to publish in its DNS, which it prints; a domain makes every address under it"
  echo "                             a valid recipient in the sandbox"
  echo "  status <identity>          its verification, whether it can send, and for a domain the status of its DKIM"
  echo "  dns <domain>               the DKIM records (three CNAMEs) to publish in the DNS of the domain"
  echo "  delete <identity>          removes it, eg to have SES send an address its verification email again"
  echo "  account                    whether the account is out of the sandbox, and its sending quota"
  echo "  production-access --website-url <url> --description <text> [--contact <address>] [--yes]"
  echo "                             asks AWS to take the account out of the sandbox, so that mail goes to any address; it says what it would send, and sends"
  echo "                             it only with --yes.  AWS reviews it, usually within a day"
  echo "The region is --region, else that of KEYCLOAK_SMTP_HOST (email-smtp.<region>.amazonaws.com) in the config of \$AFON_HOME, else AWS_REGION: the"
  echo "identities, the credentials and the SMTP endpoint all belong to one region."
}

ACTION=""
IDENTITY=""
REGION=""
WEBSITE=""
DESCRIPTION=""
CONTACT=""
YES=false
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --yes) YES=true; shift ;;
    --region|--website-url|--description|--contact)
      if [ $# -lt 2 ]; then echo "- $1 needs a value"; usage; exit 1; fi
      case "$1" in
        --region) REGION="$2" ;;
        --website-url) WEBSITE="$2" ;;
        --description) DESCRIPTION="$2" ;;
        --contact) CONTACT="$2" ;;
      esac
      shift 2 ;;
    -*) echo "- Unknown option '$1'"; usage; exit 1 ;;
    *)
      if [ -z "$ACTION" ]; then
        ACTION="$1"
      elif [ -z "$IDENTITY" ]; then
        IDENTITY="$1"
      else
        echo "- Only an action and an identity are given, not also '$1'"; usage; exit 1
      fi
      shift ;;
  esac
done
if [ -z "$ACTION" ]; then
  usage; exit 1
fi

# an identity is an address or a domain, of the usual characters, and not one that begins with a dash, which would be an option
ADDRESS_PATTERN='^[A-Za-z0-9][A-Za-z0-9._%+-]*@[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,}$'
DOMAIN_PATTERN='^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,}$'
require_identity() {
  if [ -z "$IDENTITY" ]; then
    echo "- $ACTION needs an identity: an address or a domain"; usage; exit 1
  fi
  if ! [[ "$IDENTITY" =~ $ADDRESS_PATTERN ]] && ! [[ "$IDENTITY" =~ $DOMAIN_PATTERN ]]; then
    echo "- '$IDENTITY' is neither an email address nor a domain"; exit 1
  fi
}
require_domain() {
  if [ -z "$IDENTITY" ] || ! [[ "$IDENTITY" =~ $DOMAIN_PATTERN ]]; then
    echo "- $ACTION needs a domain, eg example.org"; usage; exit 1
  fi
}

if ! command -v aws > /dev/null 2>&1; then
  echo "- the AWS CLI is needed and is not installed"
  exit 1
fi

# the region of SES: the one of the SMTP host that the config names, as it is where the identities are
if [ -z "$REGION" ] && [ -n "${AFON_HOME:-}" ] && [ -f "$AFON_HOME/config/afon.properties" ] && [ -n "${AFON_ROOT:-}" ]; then
  HOST="$(grep -h '^KEYCLOAK_SMTP_HOST=' "$AFON_HOME/config/afon.properties" 2> /dev/null | tail -1 | cut -d= -f2- || true)"
  if [[ "$HOST" =~ ^email-smtp\.([a-z0-9-]+)\.amazonaws\.com$ ]]; then
    REGION="${BASH_REMATCH[1]}"
  fi
fi
REGION="${REGION:-${AWS_REGION:-}}"
if [ -z "$REGION" ]; then
  echo "- No region: give --region, or set KEYCLOAK_SMTP_HOST to the SES endpoint of the region, or AWS_REGION"
  exit 1
fi

# prints the DKIM records of a domain, which are three CNAMEs whose names and values are made of its tokens
print_dns() {
  local tokens
  tokens="$(aws sesv2 get-email-identity --email-identity "$1" --region "$REGION" --query 'DkimAttributes.Tokens' --output text)"
  if [ -z "$tokens" ] || [ "$tokens" = "None" ]; then
    echo "- $1 has no DKIM tokens: it is not a domain, or its DKIM is not Easy DKIM"
    return 1
  fi
  echo "- Publish these records in the DNS of $1:"
  local token
  for token in $tokens; do
    echo "${token}._domainkey.$1  CNAME  ${token}.dkim.amazonses.com"
  done
}

case "$ACTION" in
  list)
    aws sesv2 list-email-identities --region "$REGION" \
      --query 'EmailIdentities[].{name:IdentityName,type:IdentityType,sending:SendingEnabled}' --output table
    ;;

  create)
    require_identity
    echo "- Making the identity $IDENTITY in $REGION"
    aws sesv2 create-email-identity --email-identity "$IDENTITY" --region "$REGION" > /dev/null
    if [[ "$IDENTITY" =~ $ADDRESS_PATTERN ]]; then
      echo "- SES sent $IDENTITY an email with a link: someone who reads it has to click it, within 24 hours, then check: afon-cli.sh aws-ses-identity status $IDENTITY"
    else
      print_dns "$IDENTITY"
      echo "- Then check: afon-cli.sh aws-ses-identity status $IDENTITY"
    fi
    ;;

  status)
    require_identity
    if [[ "$IDENTITY" =~ $ADDRESS_PATTERN ]]; then
      aws sesv2 get-email-identity --email-identity "$IDENTITY" --region "$REGION" \
        --query '{identity:IdentityType,status:VerificationStatus,sending:VerifiedForSendingStatus}' --output table
    else
      aws sesv2 get-email-identity --email-identity "$IDENTITY" --region "$REGION" \
        --query '{identity:IdentityType,status:VerificationStatus,sending:VerifiedForSendingStatus,dkim:DkimAttributes.Status}' --output table
    fi
    ;;

  dns)
    require_domain
    print_dns "$IDENTITY"
    ;;

  delete)
    require_identity
    echo "- Deleting the identity $IDENTITY in $REGION"
    aws sesv2 delete-email-identity --email-identity "$IDENTITY" --region "$REGION"
    ;;

  account)
    aws sesv2 get-account --region "$REGION" \
      --query '{production:ProductionAccessEnabled,sending:SendingEnabled,max24HourSend:SendQuota.Max24HourSend,sentLast24Hours:SendQuota.SentLast24Hours,maxPerSecond:SendQuota.MaxSendRate}' --output table
    ;;

  production-access)
    if [ -n "$IDENTITY" ]; then
      echo "- production-access takes no identity, not '$IDENTITY'"; usage; exit 1
    fi
    if [ -z "$WEBSITE" ] || [ -z "$DESCRIPTION" ]; then
      echo "- production-access needs --website-url and --description"; usage; exit 1
    fi
    if ! [[ "$WEBSITE" =~ ^https?://[^[:space:]]+$ ]]; then
      echo "- '$WEBSITE' is not a URL: https://..."; exit 1
    fi
    ARGS=(--region "$REGION" --production-access-enabled --mail-type TRANSACTIONAL --website-url "$WEBSITE" --use-case-description "$DESCRIPTION")
    if [ -n "$CONTACT" ]; then
      if ! [[ "$CONTACT" =~ $ADDRESS_PATTERN ]]; then
        echo "- '$CONTACT' is not an email address"; exit 1
      fi
      ARGS+=(--additional-contact-email-addresses "$CONTACT")
    fi
    echo "- Would ask AWS to take the SES account in $REGION out of the sandbox: transactional mail, website $WEBSITE"
    echo "  Use case: $DESCRIPTION"
    if [ "$YES" != "true" ]; then
      echo "- Nothing is sent: add --yes to send the request"
      exit 0
    fi
    aws sesv2 put-account-details "${ARGS[@]}"
    echo "- Sent: AWS reviews it, usually within a day, and may ask for more: check with afon-cli.sh aws-ses-identity account"
    ;;

  *)
    echo "- Unknown action '$ACTION'"; usage; exit 1
    ;;
esac
