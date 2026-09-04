# Roles and permissions required to create and deploy to Azure env

## User Access Administrator: single subscription-scoped grant, PIM-eligible

Four steps across this document need `Microsoft.Authorization/roleAssignments/write`, granting Storage Blob Data Contributor, Storage Blob Delegator, Network Contributor, Azure Kubernetes Service RBAC Cluster Admin, Key Vault Secrets Officer, and Key Vault Certificates Officer, to various principals as the deployment progresses. Rather than four separately scoped UAA grants, this project uses **one User Access Administrator assignment, at subscription scope, restricted by an Azure ABAC condition to exactly these six roles**, and made eligible via PIM rather than standing.

**Role definition IDs used in the condition below**

- Storage Blob Data Contributor: `ba92f5b4-2d11-453d-a403-e96b0029c9fe`
- Storage Blob Delegator: `db58b8e5-c6ad-4a2a-8342-4190687cbf4a`
- Network Contributor: `4d97b98b-1d4f-4787-a291-c67834d212e7`
- Azure Kubernetes Service RBAC Cluster Admin: `b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b`
- Key Vault Secrets Officer: `b86a8fe4-44ce-4948-aee5-eccb2c155cd7`
- Key Vault Certificates Officer: `a4417e6f-fecd-4de8-b567-7b0420556985`

**How to apply it in the Portal, as a PIM-eligible assignment:**

1. In Privileged Identity Management, under the subscription's **Azure resources > Add assignments**, select **User Access Administrator** as the role.
2. Set **Assignment type** to **Eligible**, not Active, so it requires activation rather than being standing.
3. On the role's **Conditions** tab (this only appears for roles that include `roleAssignments/write` or `.../delete`), select **Allow user to only assign selected roles to selected principals (fewer privileges)**.
4. In the role picker, select all six roles listed above, and only those six. Any attempt to assign a role outside this list, at any scope in the subscription, will be denied by the condition, even though the underlying role is the unrestricted built-in.
5. Complete the assignment. The deployer now sees this as an eligible role in PIM and must activate it, with a duration and justification, before `roleAssignments/write` becomes usable, exactly as PIM already governs their Contributor access.

**Azure CLI** (the underlying eligible role assignment, PIM-eligible assignments are set up through PIM itself rather than plain `az role assignment create`, but the condition syntax is the same either way):

```bash
CONDITION="((!(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})) OR (@Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {ba92f5b4-2d11-453d-a403-e96b0029c9fe, db58b8e5-c6ad-4a2a-8342-4190687cbf4a, 4d97b98b-1d4f-4787-a291-c67834d212e7, b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b, b86a8fe4-44ce-4948-aee5-eccb2c155cd7, a4417e6f-fecd-4de8-b567-7b0420556985})) AND ((!(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})) OR (@Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {ba92f5b4-2d11-453d-a403-e96b0029c9fe, db58b8e5-c6ad-4a2a-8342-4190687cbf4a, 4d97b98b-1d4f-4787-a291-c67834d212e7, b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b, b86a8fe4-44ce-4948-aee5-eccb2c155cd7, a4417e6f-fecd-4de8-b567-7b0420556985}))"

az role assignment create \
  --assignee "<DEPLOYER_OBJECT_ID>" \
  --role "User Access Administrator" \
  --scope "/subscriptions/<sub-id>" \
  --condition "$CONDITION" \
  --condition-version "2.0"
```

**PowerShell (Az module)**

```powershell
$condition = "((!(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})) OR (@Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {ba92f5b4-2d11-453d-a403-e96b0029c9fe, db58b8e5-c6ad-4a2a-8342-4190687cbf4a, 4d97b98b-1d4f-4787-a291-c67834d212e7, b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b, b86a8fe4-44ce-4948-aee5-eccb2c155cd7, a4417e6f-fecd-4de8-b567-7b0420556985})) AND ((!(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})) OR (@Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {ba92f5b4-2d11-453d-a403-e96b0029c9fe, db58b8e5-c6ad-4a2a-8342-4190687cbf4a, 4d97b98b-1d4f-4787-a291-c67834d212e7, b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b, b86a8fe4-44ce-4948-aee5-eccb2c155cd7, a4417e6f-fecd-4de8-b567-7b0420556985}))"

New-AzRoleAssignment `
  -ObjectId "<DEPLOYER_OBJECT_ID>" `
  -RoleDefinitionName "User Access Administrator" `
  -Scope "/subscriptions/<sub-id>" `
  -Condition $condition `
  -ConditionVersion "2.0"
```

Both commands above create the underlying eligible assignment with its condition. Actual PIM eligible-role setup (marking it Eligible rather than Active, setting activation duration limits, requiring justification or approval) still needs to be configured through PIM itself.

### ## Script create-admin.sh

Run once, by whoever bootstraps the multi-tenant app.

Every command in `create-admin.sh` is an `az ad` call against the Entra ID directory, nothing touches Azure Resource Manager (ARM), so no subscription or resource-scoped RBAC role applies here.

**Constraints specific to the OUH tenant, worth stating upfront:**

- Self-service app registration is disabled tenant-wide, so the "default tenant setting" fallback the original table relied on for `az ad app create` does not apply. A directory-level grant is required for every write in this table.
- If the identity running this script are **B2B guest users**, not tenant members. OUH's tenant is configured with **"Guest users have limited access to properties and memberships of directory objects"**. Under this setting, guests explicitly **cannot enumerate app registrations** they don't already own or have been granted visibility to. So the "no role needed by default" assumption in the original table does **not** hold for the read rows either.
- Given the above, the minimum-privilege approach here is **per-object ownership**, not a tenant-wide Entra ID role. Ownership is scoped to the specific app registration it's granted on; none of the built-in roles (Application Administrator, Cloud Application Administrator, Application Developer) can be scoped that narrowly, as they are all tenant-wide grants that apply to every app registration in the directory.

| Command                           | Operation type | Required permission                      | Minimum grant                                                                                                                                                                                                                                                                                              |
| --------------------------------- | -------------- | ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `az ad app list --display-name`   | read           | Read application registrations           | Requires **ownership** of the app to succeed for the guest devs. Under OUH's confirmed guest setting, a guest cannot enumerate an app they are not yet an owner of, so this call will not reliably find the app on a guest's first attempt, ownership must be granted first (see bootstrap sequence below) |
| `az ad app create`                | write          | Create an application registration       | Cannot be scoped to "this app only" since the app doesn't exist yet. Requires **Application Developer** (the narrowest built-in role that permits creating a new app registration) for the one-time bootstrap; the creator is automatically made an owner of the app it creates                            |
| `az ad sp show --id`              | read           | Read service principal                   | **Ownership** of the app is sufficient once the app and its owner assignment exist                                                                                                                                                                                                                         |
| `az ad sp create --id`            | write          | Create the service principal for the app | **Ownership** of the app is sufficient, the creator of the app registration is automatically its owner, and owners can create the associated service principal without any tenant-wide role                                                                                                                |
| `az ad app credential reset --id` | write          | Add/reset the app's client secret        | **Ownership** of the app is sufficient. This is the row the whole approach is optimised for: once ownership is in place, credential reset never needs a tenant-wide role, for this app or any future reset on it                                                                                           |

### Confirmed bootstrap sequence for this tenant

Given OUH's guest visibility setting, this order must be followed, out-of-order execution by a guest account will fail on the first call:

1. Someone holding **Application Developer** (or a higher role) runs `az ad app create` to create the app. They become its owner automatically. This step cannot be run by a guest developer under the current tenant setting, since it requires a tenant-wide role, not ownership, and there's nothing to own yet.
2. That same person immediately runs `az ad app owner add --id <APP_ID> --owner-object-id <GUEST_OBJECT_ID>` for each guest developer who needs to work with the app.
3. Only after step 2 should a guest developer attempt `az ad app list`, `az ad sp show`, `az ad sp create`, or `az ad app credential reset` against this app. From this point on, ownership alone covers every remaining row in the table, no tenant-wide role needed.

### Synthesis

**Entra ID roles required:**

- **Application Developer**, tenant-wide, but only for the one-time `az ad app create` (and the immediate follow-up `az ad app owner add`) bootstrap step, since neither can be scoped to an app that doesn't exist yet. Whoever holds this role runs `create-admin.sh` for the very first time, and adds the guest developers as owners in the same session.
- After that, **no tenant-wide role is required for anything else in this table**. Ownership of the specific app registration covers every subsequent read, service principal creation, and credential reset for the guest developers, scoped to exactly that app and nothing else in the directory.

**RBAC roles required: none.**

## Script create.sh

**Deployer account vs. operational identities, the distinction that matters here:**

- The **deployer account** is whoever runs `create.sh`, a human (or a deployment service principal, if this is automated end to end) that needs to *create* resources and *create* role assignments granting access to those resources.
- The **operational identities** are the things that end up *holding* the role assignments the deployer creates, principally the multi-tenant app's service principal, which receives Storage Blob Data Contributor and Storage Blob Delegator so Polaris can vend credentials at runtime.

These should never be the same permission set. Operational identities only need to be the **target** of a role assignment, never able to create one themselves. If an operational identity ever shows up holding `Microsoft.Authorization/roleAssignments/write`, that's worth treating as a security finding, not routine, since it would mean the app itself could grant roles to arbitrary principals, well beyond what "vend storage credentials" requires.

**RBAC roles required, deployer account, consolidated across the script:**

- **Contributor**, subscription scope for `az provider register` specifically, and resource group scope for resource group / DNS zone / storage account create
- **User Access Administrator**, resource group scope, **restricted via an Azure ABAC condition** to only the two roles this script actually needs to assign (see below), not the unrestricted built-in

**Reader is not a separate requirement, given the above.** Contributor's action list is `*` minus a small `NotActions` exclusion (role/policy/blueprint management), so it is a genuine superset of Reader everywhere Reader would otherwise apply. Since Contributor is already held at subscription scope (for `az provider register`), the subscription-level reads this script needs (`az group exists`, `az provider show`) are already covered, a standalone Reader assignment would add nothing.

**RBAC roles required, operational identity (the app's service principal):**

- **Storage Blob Data Contributor** and **Storage Blob Delegator**, storage account scope only. This is what the deployer's constrained UAA grant creates, the app itself is never assigned UAA or any `roleAssignments/write` permission.

| Section         | Command                                                                                  | Operation type        | ARM permission                                                | RBAC role that grants it                                                                                                                              | Scope needed    | Held by                                       |
| --------------- | ---------------------------------------------------------------------------------------- | --------------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- | --------------------------------------------- |
| Resource Group  | `az group exists`                                                                        | read                  | `Microsoft.Resources/subscriptions/resourceGroups/read`       | Covered by **Contributor**, already held at subscription scope, no separate Reader needed                                                             | Subscription    | Deployer                                      |
| Resource Group  | `az group create`                                                                        | write                 | `Microsoft.Resources/subscriptions/resourceGroups/write`      | **Contributor**                                                                                                                                       | Subscription    | Deployer                                      |
| DNS zone        | `az network dns zone show` / `create`                                                    | read/write            | `Microsoft.Network/dnsZones/read`, `.../write`                | **Contributor** (or narrower: DNS Zone Contributor)                                                                                                   | Resource group  | Deployer                                      |
| Storage RP      | `az provider show`                                                                       | read                  | `Microsoft.Resources/subscriptions/providers/read`            | Covered by **Contributor**, already held at subscription scope, no separate Reader needed                                                             | Subscription    | Deployer                                      |
| Storage RP      | `az provider register`                                                                   | action                | `Microsoft.Resources/subscriptions/providers/register/action` | **Contributor**, must be at **subscription** scope specifically; RG-scoped Contributor isn't enough since provider registration is subscription-level | Subscription    | Deployer                                      |
| Storage Account | `az storage account show` / `create`                                                     | read/write            | `Microsoft.Storage/storageAccounts/read`, `.../write`         | **Contributor** (or Storage Account Contributor)                                                                                                      | Resource group  | Deployer                                      |
| Buckets         | all three `az storage container exists` / `create` calls (uniformly `--auth-mode login`) | data-plane read/write | Blob data actions                                             | **Storage Blob Data Contributor** and **Storage Blob Delegator**                                                                                      | Storage account | Operational (app's service principal)         |
| Role Assignment | `account-principal.sh` (`az account get-access-token`, decoded locally for `oid`)        | read, ARM only        | none, reads a claim out of a token already issued for ARM     | none (default for any signed-in identity, user or service principal)                                                                                  | n/a             | Deployer                                      |
| Role Assignment | `az role assignment list`                                                                | read                  | `Microsoft.Authorization/roleAssignments/read`                | Covered by the constrained **User Access Administrator** grant (its `*/read` action), no separate Reader needed                                       | Subscription    | Deployer                                      |
| Role Assignment | `az role assignment create`                                                              | write                 | `Microsoft.Authorization/roleAssignments/write`               | **User Access Administrator, constrained by condition**, not the plain built-in                                                                       | Subscription    | Deployer only, never the operational identity |

### ### Synthesis

**Entra ID roles required: none**

**RBAC roles required, deployer account: Contributor (subscription and resource group), User Access Administrator constrained by condition to Storage Blob Data Contributor and Storage Blob Delegator only (resource group). No standalone Reader assignment needed, Contributor and the constrained UAA grant already cover every read this script performs.**

**RBAC roles required, operational identity (app's service principal): Storage Blob Data Contributor and Storage Blob Delegator only (storage account scope), never User Access Administrator or any `roleAssignments/write` permission**

## Script create.sh (custom roles for stricter security)

The tables above use standard built-in roles. If the partner's security requires least-privilege custom roles instead of built-ins, custom roles can be created.

Two considerations before discussing the roles:

- **`Microsoft.Resources/subscriptions/providers/register/action`** is the only action that must be subscription-scoped — ARM has no resource-group-level provider registration. If the partner registers `Microsoft.Storage` themselves once, this drops to a read-only check (`Microsoft.Resources/subscriptions/providers/read`), and no subscription-scope *write* action is needed at all.
- If the partner pre-creates the target resource group, `Microsoft.Resources/subscriptions/resourceGroups/write` also drops out — the exists-check always skips.

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

### Note: this is not a strict tightening of the built-in path

Three things worth flagging before treating the custom-role variant as automatically the more secure option:

- **Role 4 grants unconditional `Microsoft.Authorization/roleAssignments/write`, with no equivalent to the ABAC role-list restriction used in the built-in section above.** Azure ABAC conditions are not supported on `Microsoft.Authorization` actions, they are silently dropped if attempted, so a custom role cannot restrict *which* roles the holder is allowed to assign, only *where* (the scope, here narrowed to the storage account rather than the resource group). Held under Role 4, the deployer could assign any built-in or custom role, including Owner or User Access Administrator, to any principal within that storage account scope. On this specific dimension, Role 4 is broader than the constrained built-in UAA grant from the section above, narrower scope, but no role-list protection at all.
- **This section doesn't state what the operational identity (the app's service principal) needs.** All four custom roles describe what the deployer needs to run `create.sh`. Regardless of whether the deployer uses these custom roles or the built-in-plus-condition approach, the app's service principal still needs the built-in **Storage Blob Data Contributor** and **Storage Blob Delegator** roles at storage account scope to operate at runtime, Role 3 only covers the deployer's one-time container creation, it doesn't substitute for the app's ongoing data-plane permissions.
- **Role 1 only ever contains read actions.** The prose above describes provider registration write "dropping out" if the partner pre-registers `Microsoft.Storage`, but Role 1 as written never included that write action, so there's no documented variant of Role 1 for the case where OUH does not pre-register the provider, unlike the built-in table, which handles both cases explicitly. Worth confirming with Emrys whether pre-registration is assumed as a hard prerequisite here, or whether a second version of Role 1 is needed.

## AKS cluster

`AzureCloudProvider` (`afon-cdk`) drives `tofu apply` to build the AKS cluster and its
networking inside a **pre-existing** resource group. Unlike `create.sh`, which authenticates as the `AZURE_SP_ID` service principal, this stack runs as whichever identity is logged in via `az login`, typically the operator's own Entra user.

With `AZURE_RESOURCE_PROVIDER_REGISTRATIONS=none` (see `templates/config/afon-azure.properties`), `tofu` does not attempt to auto-register resource providers, so no subscription-level `*/register/action` right is needed at apply time, but the resource providers below must already be registered on the subscription, or the first write against each will 403.

**Deployment user vs. operational resource identity, the distinction that matters here:**

- The **deployment user** is whoever runs `tofu apply`, creates every resource in the table below, and creates both role assignments.
- The **operational resource identity** is `aks-identity`, the `UserAssignedIdentity` the deployer creates for the AKS cluster itself. It only ever **receives** one role assignment (Network Contributor on the subnet), it never creates anything and never holds `roleAssignments/write`. If `aks-identity` ever shows up holding role-assignment write capability, that would be a genuine finding, the same principle from the `create.sh` sections above applies here too.

| Resource / step                                                                                                   | Operation       | ARM permission                                                                                                                                                                | Minimum built-in role                                                                                                                     | Scope          | Held by                          |
| ----------------------------------------------------------------------------------------------------------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | -------------- | -------------------------------- |
| Resource group lookup                                                                                             | read            | `Microsoft.Resources/subscriptions/resourceGroups/read`                                                                                                                       | **Reader**                                                                                                                                | Resource group | Deployment user                  |
| `az aks get-credentials` (create-time provisioner)                                                                | read            | `Microsoft.ContainerService/managedClusters/read` (kubeconfig is AAD-only, no `listClusterAdminCredential/action` needed since `localAccountDisabled=true`)                   | **Reader** (this is a read; it does not need Contributor, that only applied because Contributor is already held for the write rows below) | Resource group | Deployment user                  |
| `UserAssignedIdentity` (`aks-identity`)                                                                           | write           | `Microsoft.ManagedIdentity/userAssignedIdentities/write`                                                                                                                      | **Contributor** (no narrower built-in spans identity, network, and AKS writes together, see note below)                                   | Resource group | Deployment user                  |
| `VirtualNetwork` (`aks-vnet`)                                                                                     | write           | `Microsoft.Network/virtualNetworks/write`                                                                                                                                     | **Contributor**                                                                                                                           | Resource group | Deployment user                  |
| `NetworkSecurityGroup` (`aks-nsg`)                                                                                | write           | `Microsoft.Network/networkSecurityGroups/write`                                                                                                                               | **Contributor**                                                                                                                           | Resource group | Deployment user                  |
| `Subnet` (`aks-subnet`)                                                                                           | write           | `Microsoft.Network/virtualNetworks/subnets/write`                                                                                                                             | **Contributor**                                                                                                                           | Resource group | Deployment user                  |
| `SubnetNetworkSecurityGroupAssociation`                                                                           | write           | `Microsoft.Network/virtualNetworks/subnets/write`, `Microsoft.Network/networkSecurityGroups/join/action`                                                                      | **Contributor**                                                                                                                           | Resource group | Deployment user                  |
| `KubernetesCluster` (`aks`)                                                                                       | write           | `Microsoft.ContainerService/managedClusters/write`, `Microsoft.Network/virtualNetworks/subnets/join/action`, `Microsoft.ManagedIdentity/userAssignedIdentities/assign/action` | **Contributor**                                                                                                                           | Resource group | Deployment user                  |
| `RoleAssignment` (`aks-identity-network-contributor`: grants Network Contributor to the AKS identity)             | write           | `Microsoft.Authorization/roleAssignments/write`                                                                                                                               | **User Access Administrator***, via the consolidated subscription-scoped PIM-eligible grant                                               | Subscription   | Deployment user                  |
| `RoleAssignment` (`tofu-cluster-admin`: grants Azure Kubernetes Service RBAC Cluster Admin to the caller)         | write           | `Microsoft.Authorization/roleAssignments/write`                                                                                                                               | **User Access Administrator**, via the consolidated subscription-scoped PIM-eligible grant                                                | Subscription   | Deployment user                  |
| `kubectl auth can-i ...` (waits for the above to propagate)                                                       | in-cluster read | n/a (Kubernetes RBAC via the Azure RBAC grant above, not ARM)                                                                                                                 | n/a                                                                                                                                       | n/a            | Deployment user                  |
| Runtime networking (grants Network Contributor to the AKS identity, per `aks-identity-network-contributor` above) | ongoing         | subnet join, load balancer / route table management for AKS's own ingress and Service provisioning                                                                            | **Network Contributor**                                                                                                                   | Subnet         | **Operational (`aks-identity`)** |

### Reader is folded into Contributor where both apply

As with the earlier `create.sh` table, a standalone Reader assignment is only needed where Contributor isn't already held at the same scope. Since Contributor is held at resource group scope for every write row here, and both read rows (resource group lookup, `az aks get-credentials`) are also resource-group scoped, Contributor alone covers them, no separate Reader grant is needed in practice. Reader is listed above to show what each row would need in isolation.

### Going narrower than Contributor, if wanted

Contributor is the genuine minimum **single built-in role** that spans everything the deployment user creates here, there is no one built-in narrower than Contributor that covers `Microsoft.ManagedIdentity`, `Microsoft.Network`, and `Microsoft.ContainerService` writes together. If narrower is wanted, **three** built-ins combined would cover the same ground with less over-reach than blanket Contributor:

- **Managed Identity Contributor**, for the `UserAssignedIdentity` row
- **Network Contributor**, for the VNet, NSG, subnet, and subnet-NSG-association rows
- **Azure Kubernetes Service Contributor Role**, for the `KubernetesCluster` row

This is worth doing if the resource group ever holds anything besides this AKS deployment, since blanket Contributor at resource group scope would also grant the deployment user unrestricted write access to those, not just the AKS-related resources. Three combined built-ins, each scoped to the specific resource type, avoid that spillover. If the resource group is dedicated entirely to this AKS deployment and nothing else, the practical difference is smaller, but the spillover risk is worth naming either way.

### Resource providers that must already be registered

- `Microsoft.Network`
- `Microsoft.ManagedIdentity`
- `Microsoft.ContainerService`
- `Microsoft.Compute` (AKS node pools)
- `Microsoft.Authorization` (role assignments, usually pre-registered on every subscription)

### Synthesis

**RBAC roles required, deployment user, on the resource group that holds the cluster:**

- **Contributor**, covers every resource create/update in the table (identity, VNet, subnet, NSG, association, AKS cluster) and the credential read; no standalone Reader needed, Contributor already covers both read rows at the same scope. Consider the three-role split above (Managed Identity Contributor, Network Contributor, Azure Kubernetes Service Contributor Role) instead of blanket Contributor if the resource group holds other sensitive resources, such as the Key Vault or storage account from earlier sections.
- **User Access Administrator**, granted once via the consolidated, subscription-scoped, PIM-eligible assignment at the top of this document, which covers the Network Contributor and Azure Kubernetes Service RBAC Cluster Admin grants alongside the storage and Key Vault roles. 

**RBAC roles required, operational resource identity (`aks-identity`):**

- **Network Contributor**, subnet scope only. This is what the deployment user's scoped UAA grant creates, `aks-identity` is never assigned UAA or any `roleAssignments/write` permission itself.

Plus confirmation that `Microsoft.Network`, `Microsoft.ManagedIdentity`, `Microsoft.ContainerService`,`Microsoft.Compute`, and `Microsoft.Authorization` are already registered on the subscription,
since `AZURE_RESOURCE_PROVIDER_REGISTRATIONS=none` means `tofu` will not register them itself.

## Key Vault (keyvault.sh / issue-cert.sh)

`keyvault.sh create` provisions the vault with `--enable-rbac-authorization true`, so unlike the legacy vault access-policy model, read/write access to secrets and certificates inside it is governed by Azure RBAC role assignments **on the vault resource**, same as any other ARM resource.
The script self-assigns one such role to the signed-in user right after creating the vault.

**Contributor cannot read secrets or certificates, categorically.** This is worth stating precisely rather than left implicit: Contributor (and Key Vault Contributor) grant **control-plane** access only, creating, deleting, and reading the vault resource's own properties, they grant zero access to the values stored inside it. Every row in the table below that touches actual secret or certificate content correctly requires Secrets Officer or Certificates Officer, never Contributor, this document has this.

**No separate operational identity in this section, worth naming as a difference from the two sections above.** In `create.sh` and the AKS stack, a deployer creates resources and grants roles to a distinct operational identity (the app's service principal, `aks-identity`) that then operates independently. Here, every row is performed by, and every role held by, the same single identity, the signed-in user who runs `keyvault.sh`. There is no separate principal that receives a grant and continues operating afterward, the person who bootstraps the vault is also the one who uses it. Worth keeping in mind when reading the table below, "Held by" would be identical on every row, so it's omitted here rather than repeated pointlessly.

| Command                                                                                                                                                 | Operation type        | Required permission                                       | RBAC role that grants it                                                                                                                                                                          | Scope          |
| ------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| `az keyvault show`                                                                                                                                      | control-plane read    | `Microsoft.KeyVault/vaults/read`                          | Covered by **Contributor**, already held at resource group scope, no separate Reader needed                                                                                                       | Resource group |
| `az keyvault create`                                                                                                                                    | control-plane write   | `Microsoft.KeyVault/vaults/write`                         | **Contributor** (or narrower: Key Vault Contributor)                                                                                                                                              | Resource group |
| `az keyvault delete`                                                                                                                                    | control-plane delete  | `Microsoft.KeyVault/vaults/delete`                        | **Contributor** (or narrower: Key Vault Contributor)                                                                                                                                              | Resource group |
| `az keyvault purge`                                                                                                                                     | control-plane action  | `Microsoft.KeyVault/locations/deletedVaults/purge/action` | **Contributor**, must be at **subscription** scope, this is the only action in this whole document with no resource-group-scoped equivalent, an Azure platform constraint, not a choice made here | Subscription   |
| `az role assignment create --role "Key Vault Secrets Officer"` / `"Key Vault Certificates Officer"` (`keyvault.sh` self-grants)                         | write                 | `Microsoft.Authorization/roleAssignments/write`           | **User Access Administrator**                                                                                                                                                                     | Subscription   |
| `az keyvault secret set` / `secret show` / `secret list` (`issue-cert.sh`, `keyvault.sh list`)                                                          | data-plane write/read | secrets data actions                                      | **Key Vault Secrets Officer**, self-granted by `keyvault.sh create`                                                                                                                               | Vault          |
| `az keyvault certificate issuer create` / `show`, `certificate create`, `certificate pending show` (`issue-cert.sh`, `digicert`/`globalsign` path only) | data-plane write/read | certificates data actions                                 | **Key Vault Certificates Officer**, self-granted by `keyvault.sh create`                                                                                                                          | Vault          |

### Reader is folded into Contributor here too

Same pattern as the other sections: `az keyvault show` is a read, but Contributor is already held at resource group scope for create/delete, so it covers this read as well, no standalone Reader assignment is needed in practice.

### The self-grant step is a genuine, if brief, self-elevation, worth being deliberate about

The signed-in user needs `roleAssignments/write` at vault scope so `keyvault.sh create` can grant Secrets Officer and Certificates Officer to themselves immediately after creating the vault. This is a legitimate, common bootstrap pattern, the same shape as the app-ownership bootstrap in the very first section of this document, but worth naming plainly: for the short window this grant exists, the signed-in user could use it to grant themselves (or anyone) a broader role than Secrets or Certificates Officer at vault scope, not just the two roles the script actually applies, including a role that *does* grant data access, such as **Key Vault Administrator**, which this UAA grant is fully capable of assigning. Microsoft names essentially this exact risk directly in its own documentation for the legacy access-policy model, where holding Contributor lets a user grant themselves data access by editing an access policy, and while this vault uses RBAC rather than access policies, the equivalent risk here is the same shape: control-plane access (Contributor) plus a vault-scoped role-assignment grant (UAA) together add up to the ability to self-elevate to full data access, even though neither one alone provides it. As with the `create.sh` and AKS sections, `Microsoft.Authorization` actions don't support ABAC conditions, so there's no way to restrict *which* roles this grant can be used for, only scope it to the vault, which the table above already does. If this bootstrap step is going to be repeated regularly (a new vault per environment, say), worth considering whether the UAA grant should be removed immediately after the two self-grants complete, rather than left standing, since nothing in `keyvault.sh` appears to remove it afterward.

### Purge needs subscription scope, unavoidably

`az keyvault purge` is the one action across every section of this document that has no resource-group-scoped equivalent at all, Azure requires subscription-level permission to purge a soft-deleted vault, regardless of how tightly everything else is scoped. Worth deciding deliberately who holds this, given it's a wider grant than anything else in this section needs: either accept Contributor at subscription scope for whoever runs `keyvault.sh`, or keep purge as a separate, rarely-exercised manual step performed by someone who already holds broader subscription access for other reasons, rather than granting it routinely to every vault operator.

### Missing: the application's own runtime access to the vault

Everything above covers **bootstrapping** the vault, the human who creates it and issues the initial certificates. It does not cover **ongoing operational access from the application running inside AKS**. If Polaris (or Keycloak, if it also needs certs or secrets) reads, creates, or updates anything in this vault at runtime, that workload needs its own role assignment, and nothing in this document grants it one, this is a genuine gap, not a simplification.

Two candidate identities, both worth considering rather than assuming one:

- **A system-assigned managed identity** on the AKS cluster or the specific workload, tied 1:1 to that resource, created and deleted automatically alongside it. Simpler to wire up, but not reusable, and not visible as its own object in the directory ahead of the resource existing, worth knowing if the deployment pipeline needs to reference its identity before the resource itself is created.
- **A user-assigned managed identity**, created independently (the same pattern already used for `aks-identity` in the AKS section), which can be provisioned ahead of time, referenced by ID before the workload exists, and reused across resources if more than one component ever needs vault access. This fits AKS's own recommended pattern for exactly this scenario, workload identity federation, where a pod's ServiceAccount is federated to a user-assigned managed identity so the pod can authenticate to Azure services, including Key Vault, without any secret stored in the cluster.

Alternatively, if Polaris already authenticates using the multi-tenant app's own service principal (the one established in `create-admin.sh`, already used for the storage credential-vending function), that same principal could be granted vault access directly instead of introducing a third identity. Worth checking with Emrys which of these their code actually expects, rather than assuming, since it changes both which principal receives the grant and how the pod obtains a token for it.

Whichever identity is chosen, the role granted to it should follow the same minimum-privilege reasoning applied elsewhere in this document, not be assumed to be the same Secrets Officer / Certificates Officer roles the bootstrap human holds:

- **Key Vault Secrets User** (read-only), if the application only ever reads secrets or certificates the bootstrap step already created, this is the minimum if Polaris just needs to fetch a cert or secret at startup or on renewal, and should be the default assumption unless the application is confirmed to write.
- **Key Vault Secrets Officer** or **Key Vault Certificates Officer**, only if the application itself creates or updates secrets or certificates at runtime, rather than just consuming what `issue-cert.sh` already produced. Granting Officer when User would suffice repeats the exact over-provisioning this whole document has been trimming elsewhere, worth confirming which the application actually does before granting the broader role by default.

This identity is purely a **recipient**, never a grantor, same principle as `aks-identity` in the AKS section and the app's service principal in `create.sh`: it should never hold `roleAssignments/write` or User Access Administrator itself, only the data-plane role(s) above, scoped to the vault.

### Synthesis

**RBAC roles required for the bootstrap, all held by the signed-in user who runs `keyvault.sh` and `issue-cert.sh`, no separate operational identity for this part:**

- **Contributor**, resource group scope, for `az keyvault create` / `delete` / `show`. No standalone Reader needed, Contributor already covers the one read row at the same scope.
- **Contributor**, subscription scope, for `az keyvault purge` specifically, this is unavoidably wider than the rest of the section needs, see above.
- **User Access Administrator**, granted once via the consolidated, subscription-scoped, PIM-eligible assignment at the top of this document, which covers the one-time self-grant of Secrets Officer and Certificates Officer alongside the other roles this deployment hands out. The vault-scoped version remains documented there under "Previously: four resource-scoped assignments" if a tighter, non-PIM setup is ever needed instead.
- **Key Vault Secrets Officer**, vault scope, self-granted by `keyvault.sh create`; covers the `acme` issuance path and `keyvault.sh list` (`secret set`/`show`/`list`).
- **Key Vault Certificates Officer**, vault scope, self-granted by `keyvault.sh create`; covers the `digicert`/`globalsign` issuance path in `issue-cert.sh` (certificate issuers and certificates, data actions Secrets Officer does not cover).

**RBAC role required for the application's own runtime access, open item, not yet confirmed with Emrys:**

- A **system-assigned or user-assigned managed identity** (see above for the trade-off between the two, user-assigned is the better fit if AKS's workload identity federation pattern is used), granted **Key Vault Secrets User** at minimum, or **Key Vault Secrets Officer** / **Key Vault Certificates Officer** only if the application genuinely writes to the vault at runtime rather than just reading what the bootstrap step created. This identity should never hold User Access Administrator or `roleAssignments/write`, it is a recipient only. Confirm with Emrys which identity mechanism their deployment actually uses, and whether Polaris reads only or also writes, before finalising this grant.
