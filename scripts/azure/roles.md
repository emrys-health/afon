# Roles and permissions required to create and deploy to Azure env

Confirmed working end-to-end on two partner subscriptions (tvs, wessex) with `create.sh` /
`destroy.sh` and the AKS stack (`tofu apply`). 
For the fuller partner-facing rationale (ABAC-condition delegation, PIM, custom roles, self-elevation risk) see
`roles_tvs.md` - this doc only covers the current implementation.

**The model:** two kinds of identity, never the same principal.

- **Deployer** - a human or CI identity, whoever is logged in via `az login` when a script runs.
Creates resources and creates role assignments.
- **Operational identities**: 
  - multi-tenant app's service principal (`APP_SP_ID`) 
  - AKS's own `aks-identity`. 
  - Only ever the *target* of a role assignment, never able to create one. `rbac.sh` checks this in `warn_if_operational_identity_can_manage_roles`.

## Entra ID - one-time app bootstrap (`create-admin.sh`, admin only)

Admin is required - one-off - to mint `APP_ID`/`APP_SP_ID`/`APP_SECRET` for a new partner. 
These values are set in `AZURE_APP_ID`/`AZURE_SP_ID`/`AZURE_APP_SECRET` in `secret-azure.properties`.
Thereafter, `create.sh` runs with `AS_ADMIN=false`.

- **Application Owner** - tenant-wide, for the one-time: 
  - `az ad app create`
  - `az ad sp show/create`
  - `az ad app credential reset`
- No standing tenant-wide role needed for the **deployer**.

## `create.sh` / `destroy.sh` - resource group, DNS, storage

Roles required for the **deployer**:

- **Contributor**
  - subscription scope - required specifically for registering the resource providers below
    (`Microsoft.Resources/subscriptions/providers/register/action` has no resource-group
    equivalent)
  - resource provider registrations
  - resource group scope - resource group / DNS zone / storage account create and delete
- **A role-assignment-granting role**
  - One of **Owner|User Access Administrator|Role Based Access Control Administrator**
  - Configurable via `AZURE_GRANT_ROLES` in `afon-azure.properties` (defaults to any of the 3 roles).
    Narrow to **User Access Administrator** if the partner prefers a least-privileg (possibly PIM-eligible grant). 
  - Used by the **deployer** to create/delete the two role assignments below on `APP_SP_ID` - never held by `APP_SP_ID` itself.

Roles granted **to the app's service principal** (`APP_SP_ID`), by the **deployer**:

- **Storage Blob Data Contributor** - both storage containers
- **Storage Blob Delegator** - the storage account

## AKS cluster (`tofu apply` / `DeployAzurePolarisStackMain`)

Runs as the **deployer's** own `az login` session too, not `APP_SP_ID`.

- **Contributor**
  - resource group scope - covers every resource it creates (`aks-identity`, VNet,
    NSG, subnet, the AKS cluster itself) and the credential read.
- **A role-assignment-granting role**
  - One of roles, as above, used to create two more role assignments:
  - **Network Contributor** → `aks-identity` (subnet scope)
  - **Azure Kubernetes Service RBAC Cluster Admin** → the deployer itself (cluster scope)

## Key Vault - `issue-cert.sh` 

TLS certs can be issued and stored in Key Vault by `issue-cert.sh`.
Partner admins are most likely to deny write access to the Key Vault. 
In this case, the `issue-cert.sh` can be run by the admin and the app only requires runtime read access to a stored TLS cert.

Role required for the **deployer**:
- **Key Vault Secrets User** - yet to be tested.

**NOTE:** Deployment works without stored certs, as cert-manager just falls back to issuing via Let's Encrypt.

## Config reference

| Property | Set where | Purpose |
|---|---|---|
| `AZURE_GRANT_ROLES` | `afon-azure.properties`, read by `rbac.sh` | Which role(s) count as "can manage role assignments". Optional, defaults to the three built-ins. |
| `AZURE_AKS_NODE_POOL` | `afon-azure.properties`, read by `AzureCloudProvider` | AKS system node pool overrides (`vmSize`, `nodeCount`). Optional, defaults to `Standard_D2s_v3`/`3`. |
