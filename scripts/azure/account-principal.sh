JWT_PAYLOAD="$(az account get-access-token --query accessToken -o tsv | cut -d. -f2 | tr '_-' '/+')"
case $(( ${#JWT_PAYLOAD} % 4 )) in
  2) JWT_PAYLOAD="${JWT_PAYLOAD}==" ;;
  3) JWT_PAYLOAD="${JWT_PAYLOAD}=" ;;
esac
PRINCIPAL_ID="$(echo "$JWT_PAYLOAD" | base64 -d 2>/dev/null | jq -r .oid)"
