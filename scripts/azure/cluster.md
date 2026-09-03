# Roles and permissions required to create the AKS cluster

`AzureCloudProvider` (`afon-cdk`) drives `tofu apply` to build the AKS cluster and its
networking inside a **pre-existing** resource group. Unlike `create.sh`, which authenticates as the `AZURE_SP_ID`
service principal, this stack runs as whichever identity is logged in via `az login` —
typically the operator's own Entra user.

With `AZURE_RESOURCE_PROVIDER_REGISTRATIONS=none` (see `templates/config/afon-azure.properties`),
`tofu` does not attempt to auto-register resource providers, so no subscription-level
`*/register/action` right is needed at apply time — but the resource providers below must
already be registered on the subscription, or the first write against each will 403.

| Resource / step | Operation | ARM permission | RBAC role that grants it | Scope |
|---|---|---|---|---|
| Resource group lookup | read | `Microsoft.Resources/subscriptions/resourceGroups/read` | Reader (or any higher role) | Resource group |
| `UserAssignedIdentity` (`aks-identity`) | write | `Microsoft.ManagedIdentity/userAssignedIdentities/write` | **Contributor** | Resource group |
| `VirtualNetwork` (`aks-vnet`) | write | `Microsoft.Network/virtualNetworks/write` | **Contributor** | Resource group |
| `NetworkSecurityGroup` (`aks-nsg`) | write | `Microsoft.Network/networkSecurityGroups/write` | **Contributor** | Resource group |
| `Subnet` (`aks-subnet`) | write | `Microsoft.Network/virtualNetworks/subnets/write` | **Contributor** | Resource group |
| `SubnetNetworkSecurityGroupAssociation` | write | `Microsoft.Network/virtualNetworks/subnets/write`, `Microsoft.Network/networkSecurityGroups/join/action` | **Contributor** | Resource group |
| `RoleAssignment` (`aks-identity-network-contributor`: grants Network Contributor to the AKS identity, subnet scope) | write | `Microsoft.Authorization/roleAssignments/write` | **Owner**, **User Access Administrator**, or **Role Based Access Control Administrator** | Resource group (assignment scope is the subnet, which is inside it) |
| `KubernetesCluster` (`aks`) | write | `Microsoft.ContainerService/managedClusters/write`, `Microsoft.Network/virtualNetworks/subnets/join/action`, `Microsoft.ManagedIdentity/userAssignedIdentities/assign/action` | **Contributor** | Resource group |
| `az aks get-credentials` (create-time provisioner) | read | `Microsoft.ContainerService/managedClusters/read` (kubeconfig is AAD-only — no `listClusterAdminCredential/action` needed since `localAccountDisabled=true`) | **Contributor** (or Reader) | Resource group |
| `RoleAssignment` (`tofu-cluster-admin`: grants Azure Kubernetes Service RBAC Cluster Admin to the caller, cluster scope) | write | `Microsoft.Authorization/roleAssignments/write` | **Owner**, **User Access Administrator**, or **Role Based Access Control Administrator** | AKS cluster (or resource group) |
| `kubectl auth can-i ...` (waits for the above to propagate) | in-cluster read | n/a (Kubernetes RBAC via the Azure RBAC grant above, not ARM) | — | — |

## Resource providers that must already be registered

- `Microsoft.Network`
- `Microsoft.ManagedIdentity`
- `Microsoft.ContainerService`
- `Microsoft.Compute` (AKS node pools)
- `Microsoft.Authorization` (role assignments — usually pre-registered on every subscription)

## Synthesis — what to ask the partner to grant

To the identity that will run `tofu apply` (the operator's own Entra user, unless a service
principal is designated instead), on the **resource group** that holds the cluster
(`AZURE_RESOURCE_GROUP`, e.g. `Test-DataFabric`):

- **Contributor** — covers every resource create/update above (identity, VNet, subnet, NSG,
  association, AKS cluster) and the credential read.
- **User Access Administrator** (or the narrower **Role Based Access Control Administrator**) —
  covers the two `RoleAssignment` resources the stack creates (Network Contributor for the AKS
  managed identity, and AKS RBAC Cluster Admin for the operator).

Plus confirmation that `Microsoft.Network`, `Microsoft.ManagedIdentity`, `Microsoft.ContainerService`,
`Microsoft.Compute`, and `Microsoft.Authorization` are already registered on the subscription,
since `AZURE_RESOURCE_PROVIDER_REGISTRATIONS=none` means `tofu` will not register them itself.
