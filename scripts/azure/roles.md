# Roles and permissions required to create Azure env

## Script create-admin.sh

Run once, by whoever bootstraps the multi-tenant app (`AS_ADMIN=true`, or ahead of time by an admin so the `APP_ID` and `APP_SECRET` can be shared).

Every command in `create-admin.sh` is an `az ad` call against the Entra ID directory — nothing touches Azure Resource Manager (ARM), so no subscription or resource-scoped RBAC role applies here.

| Command | Operation type | Required permission | Entra ID role that grants it |
|---|---|---|---|
| `az ad app list --display-name` | read | Read application registrations | none needed by default — any authenticated user can read app registrations unless the tenant restricts directory reads |
| `az ad app create` | write | Create an application registration | **Application Administrator**, **Cloud Application Administrator**, or **Application Developer** — or the default tenant setting "Users can register applications" (enabled by default); only required if self-service app registration has been disabled org-wide |
| `az ad sp show --id` | read | Read service principal | none needed by default |
| `az ad sp create --id` | write | Create the service principal for the app | Same tier as app create, or simply being an **owner** of the app (the creator is automatically an owner); if you created the app in the step above, no extra tenant-wide role is needed to create its service principal |
| `az ad app credential reset --id` | write | Add/reset the app's client secret | **Owner** of the app registration, or **Application Administrator** / **Cloud Application Administrator**; only runs when `APP_CREDENTIAL_RESET` is set |

### Synthesis

**Entra ID roles required: Application Administrator** (or the narrower **Cloud Application Administrator**) at the tenant level covers every step above.

**RBAC roles required: none.**

## Script create.sh

**RBAC roles required**, consolidated across the script:

- **Reader** — subscription scope (resource group existence check, provider registration state, role assignment reads)
- **Contributor** — subscription scope for `az provider register` specifically (provider registration is a subscription-level action; resource-group-scoped Contributor is not sufficient for this one call), and resource group scope for resource group / DNS zone / storage account create
- **Storage Blob Data Contributor** — storage account scope, for all three bucket exists/create calls (uniformly `--auth-mode login`)
- **Owner**, **User Access Administrator**, or **Role Based Access Control Administrator** — resource group scope (`Test-DataFabric`), for `az role assignment create`

| Section | Command | Operation type | ARM permission | RBAC role that grants it | Scope needed |
|---|---|---|---|---|---|
| Resource Group | `az group exists` | read | `Microsoft.Resources/subscriptions/resourceGroups/read` | Reader (or any higher role) | Subscription |
| Resource Group | `az group create` | write | `Microsoft.Resources/subscriptions/resourceGroups/write` | **Contributor** | Subscription |
| DNS zone | `az network dns zone show` / `create` | read/write | `Microsoft.Network/dnsZones/read`, `.../write` | **Contributor** (or narrower: DNS Zone Contributor) | Resource group |
| Storage RP | `az provider show` | read | `Microsoft.Resources/subscriptions/providers/read` | Reader | Subscription |
| Storage RP | `az provider register` | action | `Microsoft.Resources/subscriptions/providers/register/action` | **Contributor** — must be at **subscription** scope specifically; RG-scoped Contributor isn't enough since provider registration is subscription-level | Subscription |
| Storage Account | `az storage account show` / `create` | read/write | `Microsoft.Storage/storageAccounts/read`, `.../write` | **Contributor** (or Storage Account Contributor) | Resource group |
| Buckets | all three `az storage container exists` / `create` calls (uniformly `--auth-mode login`) | data-plane read/write | Blob data actions | **Storage Blob Data Contributor** | Storage account |
| Role Assignment | `account-principal.sh` (`az account get-access-token`, decoded locally for `oid`) | read, ARM only | none — reads a claim out of a token already issued for ARM | none (default for any signed-in identity, user or service principal) | — |
| Role Assignment | `az role assignment list` | read | `Microsoft.Authorization/roleAssignments/read` | Reader (included in virtually everything) | Resource group |
| Role Assignment | `az role assignment create` | write | `Microsoft.Authorization/roleAssignments/write` | **Owner**, **User Access Administrator**, or **Role Based Access Control Administrator** | Resource group (`Test-DataFabric`) |

### Synthesis

**Entra ID roles required: none**

**RBAC roles required: as above**

## Script create.sh (custom roles for stricter security)

The tables above use standard built-in roles. If the partner's security requires least-privilege custom roles instead of built-ins, custom roles can be created.

Two considerations before discussing the roles:

- **`Microsoft.Resources/subscriptions/providers/register/action`** is the only action that must be subscription-scoped — ARM has no resource-group-level provider registration. If the partner registers `Microsoft.Storage` themselves once, this drops to a read-only check (`Microsoft.Resources/subscriptions/providers/read`), and no subscription-scope *write* action is needed at all.
- If the partner pre-creates the target resource group (e.g. `Test-DataFabric`), `Microsoft.Resources/subscriptions/resourceGroups/write` also drops out — the exists-check always skips.

So, if `Microsoft.Storage` provider is already registered and `$AZURE_RESOURCE_GROUP` already created, the following roles apply:

**RBAC roles required (custom, in place of built-ins):**

- `afon-create-subscription-read` — subscription scope
- `afon-create-resource-group` — resource group scope
- `afon-create-containers` — storage account scope
- `afon-create-role-assignments` — storage account scope

**Role 1 — subscription scope, read-only**
```json
{
  "Name": "afon-create-subscription-read",
  "Actions": [
    "Microsoft.Resources/subscriptions/resourceGroups/read",
    "Microsoft.Resources/subscriptions/providers/read"
  ],
  "AssignableScopes": ["/subscriptions/<sub-id>"]
}
```

**Role 2 — resource group scope**
```json
{
  "Name": "afon-create-resource-group",
  "Actions": [
    "Microsoft.Network/dnsZones/read",
    "Microsoft.Network/dnsZones/write",
    "Microsoft.Storage/storageAccounts/read",
    "Microsoft.Storage/storageAccounts/write"
  ],
  "AssignableScopes": ["/subscriptions/<sub-id>/resourceGroups/<rg-name>"]
}
```
Add `Microsoft.Resources/subscriptions/resourceGroups/write` back into this role only if the resource group is not pre-created by the partner.

**Role 3 — storage account scope, container-only data access**

Narrower than the built-in `Storage Blob Data Contributor`, which also grants blob read/write/delete on data inside containers — unneeded here since `create.sh` only creates empty containers.
```json
{
  "Name": "afon-create-containers",
  "Actions": [],
  "DataActions": [
    "Microsoft.Storage/storageAccounts/blobServices/containers/read",
    "Microsoft.Storage/storageAccounts/blobServices/containers/write"
  ],
  "AssignableScopes": ["/subscriptions/<sub-id>/resourceGroups/<rg-name>/providers/Microsoft.Storage/storageAccounts/<storage-account-name>"]
}
```

**Role 4 — role assignment management, storage account scope (not resource group)**

All three role-assignment targets in `create.sh` (`BUCKET_SCOPE`, `EXTERNAL_BUCKET_SCOPE`, `STORAGE_ACCOUNT_SCOPE`) are the storage account or its children, so this doesn't need resource-group-wide scope the way the built-in `Role Based Access Control Administrator` implies — scope it to the storage account resource only:
```json
{
  "Name": "afon-create-role-assignments",
  "Actions": [
    "Microsoft.Authorization/roleAssignments/read",
    "Microsoft.Authorization/roleAssignments/write"
  ],
  "AssignableScopes": ["/subscriptions/<sub-id>/resourceGroups/<rg-name>/providers/Microsoft.Storage/storageAccounts/<storage-account-name>"]
}
```

### Synthesis

**Entra ID roles required: none**

**RBAC roles required: as above**

The custom-role variant is a like-for-like replacement of the built-in RBAC roles from the section above.
