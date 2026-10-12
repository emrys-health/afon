# Roles and permissions required to create and deploy to Azure env

## The model

We have different kinds of identity, with different principals:

- **Deployer**:
  - a human or CI identity, whoever is logged in via `az login` when a script runs.
  - Creates resources and creates role assignments.
- **Operational identities**: 
  - multi-tenant app's service principal (`APP_SP_ID`) 
  - AKS's own `aks-identity`. 

**NOTE**: Operational identities are the **target** of a role assignment, should never be able to create one. 
`rbac.sh` checks if this is not the case in `warn_if_operational_identity_can_manage_roles`.

## Entra ID - one-time app bootstrap (`create-admin.sh`, admin only)

Frequently, access to Entra ID will be denied by admin, so we have a one-time app bootstrap, where the 
admin can mint `APP_ID`/`APP_SP_ID`/`APP_SECRET` for a new Azure env. 

Once these values are set as `AZURE_APP_ID`/`AZURE_SP_ID`/`AZURE_APP_SECRET` in `secret-azure.properties`,
`create.sh` can run as non-admin (ie `AS_ADMIN=false`).

Roles required for the admin part:

- **Application Owner**
  - tenant-wide - required to run:
    - `az ad app create`
    - `az ad sp create`
    - `az ad app credential reset`
    
No standing tenant-wide role needed for the **deployer**.

## Env creation/teardown (`create.sh` / `destroy.sh`)

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
    Narrow to **User Access Administrator** if the partner prefers a least-privilege (possibly PIM-eligible) grant. 
  - Used by the **deployer** to create/delete the two role assignments below on `APP_SP_ID` - never held by `APP_SP_ID` itself.

Roles granted **to the app's service principal** (`APP_SP_ID`), by the **deployer**:

- **Storage Blob Delegator** - the storage account
- **Storage Blob Data Contributor** - the storage containers

## Deployment of AKS cluster (`tofu apply`)

Roles required for the **deployer**:

- **Contributor**
  - resource group scope - covers every resource it creates (`aks-identity`, VNet,
    NSG, subnet, the AKS cluster itself) and the credential read.
- **A role-assignment-granting role**
  - One of roles, as above for the Env creation/teardown, used to create two more role assignments:
  - **Network Contributor** → `aks-identity` (subnet scope)
  - **Azure Kubernetes Service RBAC Cluster Admin** → the deployer itself (cluster scope)

## TLS certs issuance (`issue-cert.sh`)

TLS certs can be issued and stored in Key Vault by `issue-cert.sh`.

### Storing the certs

Roles required to store the certs (either by the **deployer** or by the admin):

- **Contributor** at resource group scope is needed, create or show a Key Vault.
- **Key Vault Secrets Officer** (`Microsoft.KeyVault/vaults/secrets/*`) - required for every
  issuer path. `store_cert()` always writes the final crt+key as two named secrets via `az
  keyvault secret set`, regardless of which issuer produced them.
- **Key Vault Certificates Officer** (`certificates/*`, `certificatecas/*`,
  `certificatecontacts/write`) - only needed *additionally* for the `digicert`/`globalsign`
  issuer path (`issue_via_keyvault_ca`), which registers a CA issuer and requests the cert
  through Key Vault's own CA integration. Not needed for the `acme` path, and does **not** cover
  reading secrets.

`issue-cert.sh` issues the cert of each domain name that is configured, ie not blank, of `AZURE_POLARIS_DOMAIN_NAME`,
`AZURE_JUPYTER_DOMAIN_NAME` and `OIDC_DOMAIN_NAME`, and each of the stacks that deploy them restores its own.
For `acme` the DNS zone of each domain name must be in the resource group.

For each domain name, the two secrets stored for the TLS cert are:
```shell
KEBAB_DOMAIN_NAME="${DOMAIN_NAME//./-}"
AZURE_TLS_CRT_NAME="${KEBAB_DOMAIN_NAME}-tls-crt"
AZURE_TLS_KEY_NAME="${KEBAB_DOMAIN_NAME}-tls-key"
```

### DNS validation (`acme` only)

Role granted **to the app's service principal** (`APP_SP_ID`), by the **deployer**:

- **DNS Zone Contributor** - DNS zone scope, of each domain name.
  Needed by `acme.sh`'s `dns_azure` hook to create/delete the `_acme-challenge` TXT record that
  proves domain ownership to Let's Encrypt. Not needed by `digicert`/`globalsign` - validation
  happens through the CA's own integration with Key Vault instead, never touching Azure DNS.

**NOTE on envs using a condition-restricted `User Access Administrator`**: 
the ABAC condition only allows assigning whatever roles were explicitly listed when it was set up. 
If `DNS Zone Contributor`'s role ID isn't in that list, `az role assignment create` fails with
`AuthorizationFailed ... has an authorization with ABAC condition that is not fulfilled`, even
though the deployer holds `User Access Administrator` and the grant otherwise looks fine - a
distinct error from a plain `Forbidden`. Ask the admin to add **DNS Zone Contributor** role ID
to the condition, alongside the storage/AKS role IDs already there.

### Restoring the certs

If admin does not allow `write` access to the Key Vault, `issue-cert.sh`
can be run by the admin instead, and the app only needs runtime read access to the cert it stored.

Role required for the **deployer** to restore the certs:

- **Key Vault Secrets User** (`secrets/getSecret`, `secrets/readMetadata`) - to be held by the
  deployer since deploy-time restore step runs as a `local-exec` Terraform provisioner 
  under the deployer's own `az login` session, calling `az keyvault secret show` directly.

**NOTE:** Deployment works even without Key Vault - cert-manager just falls back to issuing via
Let's Encrypt if property `AZURE_KEY_VAULT_NAME` is not set in `secret-azure.properties`. The problem is
the limit of weekly issuances of Let's Encrypt.

## Config reference

| Property | Set where | Purpose |
|---|---|---|
| `AZURE_GRANT_ROLES` | `afon-azure.properties`, read by `rbac.sh` | Which role(s) count as "can manage role assignments". Optional, defaults to the three built-ins. |
| `AZURE_AKS_NODE_POOL` | `afon-azure.properties`, read by `AzureCloudProvider` | AKS system node pool overrides (`vmSize`, `nodeCount`). Optional, defaults to `Standard_D2s_v3`/`3`, but `azure-full` needs `nodeCount:4`. |
