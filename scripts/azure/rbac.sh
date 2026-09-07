# Shared RBAC checks for the Azure create/destroy scripts.
# Source this file to use the functions below

# Azure built-in roles that grant Microsoft.Authorization/roleAssignments/write (and /delete),
# pipe-separated. Configurable per env via AZURE_GRANT_ROLES in afon-azure.properties;
# defaults to the three roles that grant it by default. Some envs restrict this to a single role
# held as a PIM-eligible, condition-restricted 'User Access Administrator' assignment rather than
# a standing grant - set AZURE_GRANT_ROLES="User Access Administrator" there.
AZURE_GRANT_ROLES="${AZURE_GRANT_ROLES:-Owner|User Access Administrator|Role Based Access Control Administrator}"

# Builds the `az role assignment list --query` JMESPath expression for AZURE_GRANT_ROLES.
role_assignment_grant_query() {
  local IFS='|' role query=""
  for role in $AZURE_GRANT_ROLES; do
    [ -n "$query" ] && query="$query || "
    query="${query}roleDefinitionName=='$role'"
  done
  echo "[?$query].roleDefinitionName"
}

# Prints the role-assignment-granting roles (if any) held by the given principal in the subscription.
# $1: principal id to check
role_assignment_grant_roles_held() {
  local principal_id="$1"
  az role assignment list --assignee "$principal_id" --all --query "$(role_assignment_grant_query)" -o tsv
}

# True if the given principal holds role assignments roles in the subscription, i.e. can create/delete role assignments.
# $1: principal id to check
can_manage_role_assignments() {
  local principal_id="$1"
  [ -n "$(role_assignment_grant_roles_held "$principal_id")" ]
}

# Exits 1 with guidance unless the given principal can manage role assignments.
# $1: principal id to check   $2: "create" or "delete"   $3: script name to mention in the message
require_role_assignment_rights() {
  local principal_id="$1" verb="$2" script_name="$3"
  if can_manage_role_assignments "$principal_id"; then
    echo "- Current principal '$principal_id' has rights to $verb role assignments, proceeding"
  else
    echo "- FAILURE: Current principal '$principal_id' lacks RBAC rights needed to $verb role assignments."
    echo "-- If you have an PIM-eligible 'User Access Administrator' role on this subscription, activate it before re-running $script_name."
    echo "-- If you don't have that eligible role at all, ask your admin to grant it, then re-run $script_name."
    exit 1
  fi
}

# Warns (without exiting) if the given operational identity holds a role-assignment-granting role.
# Operational identities should only ever be the target of a role assignment, never able to create one
warn_if_operational_identity_can_manage_roles() {
  local principal_id="$1"
  if can_manage_role_assignments "$principal_id"; then
    echo "-- WARNING: operational identity '$principal_id' holds a role-assignment-granting role. This should never happen."
  fi
}
