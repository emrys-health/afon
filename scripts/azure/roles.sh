source $AFON_ROOT/scripts/config/afon-source.sh azure

usage(){
  echo "Usage: ${BASH_SOURCE[0]} [principal-id]"
  echo "Shows active role assignments for the given principal (default: the signed-in deployer),"
  echo "decoding any ABAC condition's role-ID allow-list into readable role names. Only shows"
  echo "assignments that are currently active - a PIM-eligible role not yet activated won't"
  echo "appear here, since it isn't usable yet either."
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ -n "${1:-}" ]; then
  PRINCIPAL_ID="$1"
else
  source $AFON_ROOT/scripts/azure/account-principal.sh
fi

# Resolves a role definition ID (GUID) to its display name, falling back to the GUID itself if
# not found (eg a custom role not visible from this scope).
role_name_for_id() {
  local id="$1" name
  name="$(az role definition list --query "[?name=='$id'].roleName | [0]" -o tsv 2>/dev/null)"
  echo "${name:-$id}"
}

# Extracts the GuidEquals {...} role-ID allow-list out of an ABAC condition string.
condition_role_ids() {
  echo "$1" | grep -oE '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' | sort -u
}

echo "- Active role assignments for principal '$PRINCIPAL_ID':"

az role assignment list --assignee-object-id "$PRINCIPAL_ID" --all \
  --query "[].{role:roleDefinitionName, scope:scope, condition:condition}" -o json |
  jq -c '.[]' |
  while IFS= read -r row; do
    ROLE="$(echo "$row" | jq -r '.role')"
    SCOPE="$(echo "$row" | jq -r '.scope')"
    CONDITION="$(echo "$row" | jq -r '.condition // empty')"

    echo ""
    echo "-- $ROLE"
    echo "   scope: $SCOPE"
    if [ -n "$CONDITION" ]; then
      echo "   condition restricts roleAssignments/write|delete to:"
      for id in $(condition_role_ids "$CONDITION"); do
        echo "     - $(role_name_for_id "$id")  ($id)"
      done
    fi
  done
