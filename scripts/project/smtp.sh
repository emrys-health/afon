#!/usr/bin/env bash
# Sends a test email through the SMTP server that the config names, with the KEYCLOAK_SMTP_* properties of the env that is active: the server
# that the realm of the central identity sends its invitations through, see docs/projects.md.  It checks the host, the credentials and the
# route from this machine, before the realm is given them.  Needs curl.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <to> [--from <address>] [--subject <text>] [--verbose] [--dry-run]"
  echo "Sends a short test email to <to>, through KEYCLOAK_SMTP_HOST at KEYCLOAK_SMTP_PORT (587 if empty), secured by KEYCLOAK_SMTP_SECURITY"
  echo "(starttls, ssl or none; starttls if empty), as KEYCLOAK_SMTP_USERNAME, with KEYCLOAK_SMTP_PASSWORD, if the server asks for them."
  echo "The properties are those of afon.properties and secret.properties in \$AFON_HOME/config, ie of the active env."
  echo "  --from <address>   the address it is sent from, by default KEYCLOAK_SMTP_FROM, which must be one that the server lets you send from"
  echo "  --subject <text>   the subject of the email, by default 'SMTP test'"
  echo "  --verbose          shows the replies of the server and the commands that the message is sent with, never the login"
  echo "  --dry-run          says what it would send, and where, and sends nothing; it never says the password"
}

TO=""
FROM=""
SUBJECT="SMTP test"
DRY_RUN=false
VERBOSE=false
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --verbose) VERBOSE=true; shift ;;
    --from|--subject)
      if [ $# -lt 2 ]; then echo "- $1 needs a value"; usage; exit 1; fi
      if [ "$1" = "--from" ]; then FROM="$2"; else SUBJECT="$2"; fi
      shift 2 ;;
    -*) echo "- Unknown option '$1'"; usage; exit 1 ;;
    *)
      if [ -n "$TO" ]; then echo "- Only one address to send to is given, not also '$1'"; usage; exit 1; fi
      TO="$1"; shift ;;
  esac
done
if [ -z "$TO" ]; then
  usage; exit 1
fi
# an address names no header or command: no space, angle bracket, quote or line break
ADDRESS_PATTERN='^[^[:space:]<>"@]+@[^[:space:]<>"@]+$'
if ! [[ "$TO" =~ $ADDRESS_PATTERN ]]; then
  echo "- '$TO' is not an email address"
  exit 1
fi
if ! command -v curl > /dev/null 2>&1; then
  echo "- curl is needed and is not installed"
  exit 1
fi
if [ -z "${AFON_HOME:-}" ]; then
  echo "- AFON_HOME is not set"
  exit 1
fi

# the properties of the env: afon.properties, then the secrets, which may use its names
source "$AFON_ROOT/scripts/config/afon-source.sh"
source "$AFON_ROOT/scripts/config/secret-source.sh"

HOST="${KEYCLOAK_SMTP_HOST:-}"
PORT="${KEYCLOAK_SMTP_PORT:-}"
SECURITY="${KEYCLOAK_SMTP_SECURITY:-}"
USERNAME="${KEYCLOAK_SMTP_USERNAME:-}"
PASSWORD="${KEYCLOAK_SMTP_PASSWORD:-}"
FROM="${FROM:-${KEYCLOAK_SMTP_FROM:-}}"
PORT="${PORT:-587}"
SECURITY="$(printf '%s' "${SECURITY:-starttls}" | tr '[:upper:]' '[:lower:]')"

if [ -z "$HOST" ]; then
  echo "- KEYCLOAK_SMTP_HOST is not set in $AFON_HOME/config/afon.properties: there is no SMTP server to test"
  exit 1
fi
if [ -z "$FROM" ]; then
  echo "- There is no address to send from: set KEYCLOAK_SMTP_FROM, or give --from"
  exit 1
fi
if ! [[ "$FROM" =~ $ADDRESS_PATTERN ]]; then
  echo "- '$FROM' is not an email address"
  exit 1
fi
if ! [[ "$PORT" =~ ^[0-9]+$ ]]; then
  echo "- KEYCLOAK_SMTP_PORT '$PORT' is not a number"
  exit 1
fi
case "$SECURITY" in
  starttls) URL="smtp://$HOST:$PORT"; SECURE=(--ssl-reqd) ;;
  ssl) URL="smtps://$HOST:$PORT"; SECURE=() ;;
  none) URL="smtp://$HOST:$PORT"; SECURE=() ;;
  *) echo "- KEYCLOAK_SMTP_SECURITY '$SECURITY' is not starttls, ssl or none"; exit 1 ;;
esac
# the subject is a line of the message
if [[ "$SUBJECT" == *$'\n'* ]] || [[ "$SUBJECT" == *$'\r'* ]]; then
  echo "- The subject is one line"
  exit 1
fi

echo "- Server: $URL ($SECURITY)"
echo "- From: $FROM"
echo "- To: $TO"
if [ -n "$USERNAME" ]; then
  echo "- Authenticating as: $USERNAME (the password is not shown)"
else
  echo "- No authentication: KEYCLOAK_SMTP_USERNAME is not set"
fi
if [ "$DRY_RUN" = "true" ]; then
  echo "- Dry run: nothing is sent"
  exit 0
fi

MESSAGE="$(mktemp)"
trap 'rm -f "$MESSAGE"' EXIT
{
  printf 'From: %s\r\n' "$FROM"
  printf 'To: %s\r\n' "$TO"
  printf 'Subject: %s\r\n' "$SUBJECT"
  printf 'Date: %s\r\n' "$(date -R)"
  printf 'Content-Type: text/plain; charset=UTF-8\r\n'
  printf '\r\n'
  printf 'This is a test of the SMTP server of the central identity, sent from %s at %s.\r\n' "$(hostname)" "$(date -u '+%Y-%m-%d %H:%M:%SZ')"
  printf 'Server: %s, as %s.\r\n' "$URL" "${USERNAME:-no user}"
} > "$MESSAGE"

# the credentials go to curl on its standard input, as a config, so that the password is on no command line, where ps would show it
curl_quote() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

ARGS=(--silent --show-error --fail-with-body --connect-timeout 15 --max-time 60 --url "$URL" ${SECURE[@]+"${SECURE[@]}"}
      --mail-from "$FROM" --mail-rcpt "$TO" --upload-file "$MESSAGE")
if [ -n "$USERNAME" ]; then
  ARGS+=(-K -)
  CONFIG="$(printf 'user = "%s:%s"\n' "$(curl_quote "$USERNAME")" "$(curl_quote "$PASSWORD")")"
else
  CONFIG=""
fi
# the dialogue with the server, which curl prints on its standard error with the client's lines after "> ": the server's replies, and the commands that
# say who the message is from and to, but never the login, whose line carries the credentials
dialogue() {
  grep -E '^(< |> (EHLO|STARTTLS|MAIL FROM|RCPT TO|DATA|QUIT))' || true
}
if [ "$VERBOSE" = "true" ]; then
  ARGS+=(--verbose)
fi
if OUTPUT="$(printf '%s' "$CONFIG" | curl "${ARGS[@]}" 2>&1)"; then
  if [ "$VERBOSE" = "true" ]; then printf '%s\n' "$OUTPUT" | dialogue; fi
  echo "- Sent: look for the email to $TO, which may be in its spam folder"
else
  STATUS=$?
  if [ "$VERBOSE" = "true" ]; then
    printf '%s\n' "$OUTPUT" | dialogue
    OUTPUT="$(printf '%s\n' "$OUTPUT" | grep -E '^curl: ' || true)"
  else
    OUTPUT="$(printf '%s\n' "$OUTPUT" | grep -E '^curl: ' || printf '%s' "$OUTPUT")"
  fi
  echo "- Could not send (curl status $STATUS): $OUTPUT"
  echo "  A login failure (67) is the credentials, a refusal of an address (55, 56) the sender or the recipient not allowed by the server."
  echo "  A weird server reply (8) is usually a refusal of the message itself, after it was sent: run it again with --verbose to see the reply, eg"
  echo "  554 'Email address is not verified' from the sandbox of SES, which only sends to verified addresses and domains, or 554 'Access denied' when the"
  echo "  IAM policy of the SMTP user does not allow ses:SendRawEmail on an identity that the send involves, the recipient's in the sandbox too."
  echo "  A timeout is the route from this machine to $HOST:$PORT."
  exit 1
fi
