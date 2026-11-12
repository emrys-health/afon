# Azure: a federated credential instead of an app secret

The Azure env [config](config.md) when the app registration has a **federated credential** and no `AZURE_APP_SECRET`.  Do the steps of [config](config.md) first, except those that give or use the secret, which this replaces: the admin part ([the Entra ID app](config.md#4-admin-part-the-entra-id-app)) creates the app, then the config ([§5](config.md#5-create-and-fill-in-the-config)) and the env ([§9](config.md#9-create-the-env-and-check-the-storage)) follow.

The admin may give the app registration a federated credential, which replaces the secret: a token of an issuer that the credential trusts, for a subject it names, acts as the app.  Then there is no `APP_SECRET`, and the deployer sets `AZURE_WORKLOAD_IDENTITY=true` in `secret-azure.properties` and leaves `AZURE_APP_SECRET` blank.  The flag is explicit and never inferred from a blank secret: with `AZURE_WORKLOAD_IDENTITY=false` (the default) a blank `AZURE_APP_SECRET` is an error.

## Why workload identity, and not managed identity

Both have no secret, but they are different things, and the flag is named after the workload identity:
- A **managed identity** is an identity that Azure attaches to an Azure resource, eg a VM, a scale set or an AKS node pool.  Code running on that resource gets tokens from Azure's metadata endpoint, so whatever runs there is trusted, with nothing to set up but the attachment.  It has no secret, but is tied to the Azure compute that it is attached to.
- A **workload identity** is an identity based on the **app registration**, which is not attached to an Azure resources, so there is no metadata endpoint to query.  Instead, the **workload** (ie the Polaris pod) proves who it is with a token signed by its own platform, ie the AKS cluster.  Entra ID exchanges the token for an Azure token of the app, because the app's **federated credential** says to trust that issuer for that subject.  So **in workload identity federation the identity belongs to the workload** and an issuer outside Azure's compute vouches for it.
- **In workload identity the trust is narrower and explicit**: for the Polaris pod it is that one service account of that one cluster, `system:serviceaccount:default:polaris`, and not every pod on a node, as it is with a managed identity.  The price is that the trust must be explicit: a credential that names the issuer, and the issuer is specific to each cluster (see below).
- The name **workload** is also that of the AKS feature that does the exchange, which the cluster setting `workloadIdentityEnabled` and the pod label `azure.workload.identity/use` belong to.  (A user-assigned managed identity can take a federated credential too, and then works the same from the pod's side, but this guide is for the app registration that the admin provides.)

## The credential needs the cluster: the token is only issued once it exists

The Polaris pod gets its token from the AKS cluster, which signs it under the cluster's own **issuer** URL, and Azure generates that URL when it creates the cluster.  The federated credential of the app must name that issuer, so **it can only be written once the cluster exists**, and again whenever the cluster is rebuilt, as its issuer is new.  Polaris needs it when it starts, so the deploy gives it between its two halves, and **the deployer must be allowed to give the app registration its credential**:

1. the deployer deploys the cluster: `afon-cli.sh manage-stack setup <stack-id>`;
2. during deployment, post cluster creation, the app is given the federated credential of the new cluster: `afon-cli.sh azure-identity true <stack-id>`;
3. the deployment continues with the rest of the stack, including Polaris, which starts with the credential in place.

If the deployer is not granted the app registration role, step 2 **fails fast**, saying why, and the rest is not deployed.

The credential of the app for the Polaris pod is:

| Issuer | Subject | Audience | Created |
|--------|---------|----------|---------|
| that of the AKS cluster, which exists only once the cluster is up | `system:serviceaccount:default:polaris` | `api://AzureADTokenExchange` | for each cluster, again if it is rebuilt |

The deployer needs no credential: they log in as themselves, with `afon-cli.sh azure-login`.

## Step by step

### Once, by the admin

1. **Create the app and its service principal, without a secret.**  As in [Run create-admin.sh](config.md#run-create-adminsh-as-the-admin), but leave `APP_CREDENTIAL_RESET` unset, so no secret is created. `azure-login` logs in to `AZURE_TENANT_ID` and selects `AZURE_SUBSCRIPTION` of the config:
   ```shell
   afon-cli.sh azure-login
   afon-cli.sh azure-create-admin [<app-name>]
   ```
   Read `APP_ID` and `APP_SP_ID` from the output, as above, and hand them to the deployer.

2. **Grant the deployer the app registration role.**  The credential of the app is new for each cluster, and the deployer assigns it, just like `azure-create` assigns the app its roles on the storage.  The **app registration role** lets the deployer change that one app registration, here its federated credentials, and does not make them an admin of anything else.  It is **an Entra ID role on the app, not an Azure role assignment on a resource**:
   ```shell
   afon-cli.sh azure-identity-admin grant [--deployer <deployer>] [--app-id <APP_ID>]
   ```
   The deployer is by default the identity that is logged in to `az`, so a deployer may assign the credential on its own app.  An admin gives can specify a different identity via `--deployer`.  The script finds its object ID, grants the role, and is idempotent.  For a deployer that is a service principal, it also needs the Graph application permission `Application.ReadWrite.OwnedBy`.

   To test that a deploy without the role fails fast, or to retire a deployer, take the role back with `afon-cli.sh azure-identity-admin ungrant [--deployer <deployer>]`.  It refuses, unless `--force`, to take the role from the identity that is logged in or from the last one that has it, as then only a tenant admin (Application Administrator or Global Administrator) can give it back.

### The deployer, in a terminal

1. **Set the values from the admin** in `secret-azure.properties`, with the `AZURE_WORKLOAD_IDENTITY` flag, and leave the secret blank:
   ```shell
   AZURE_TENANT_ID=<TENANT_ID>
   AZURE_APP_ID=<APP_ID>
   AZURE_SP_ID=<APP_SP_ID>
   AZURE_APP_SECRET=
   AZURE_WORKLOAD_IDENTITY=true
   ```

2. **Create the env and deploy the stack**, as in [§9](config.md#9-create-the-env-and-check-the-storage).  The deployer needs the roles of [roles.md](../../../scripts/azure/roles.md), and, with the flag, the app registration role of step 2 of the admin:
   ```shell
   afon-cli.sh azure-login
   afon-cli.sh azure-create <stack-id>
   afon-cli.sh manage-stack setup <stack-id>
   ```
   With the `AZURE_WORKLOAD_IDENTITY` flag set, a stack with Polaris is deployed in three steps, because the credential of the app trusts the issuer of the cluster, which **exists only once `tofu apply` has created the cluster**, and Polaris needs the credential when it starts:

   | Step | What it does |
   |------|--------------|
   | `deploy_<stack>_cluster` | `tofu apply` of the cluster only (and what it needs), which gives the cluster its issuer |
   | `ensure_federated_credential_<stack>` | gives the app the credential of the cluster, as `azure-identity true <stack-id>` does, and shows it.  A rebuilt cluster has the same name and a new issuer, so its credential replaces that of the previous build.  If the deployer does not have the app registration role, it **fails fast**, saying why, and the rest is not deployed: once the admin has granted it, run the same command again, as the steps before are idempotent |
   | `deploy_<stack>_stack` | `tofu apply` of the rest, including Polaris, which then starts with the credential in place: its service account `polaris` in `default`, annotated with `AZURE_APP_ID`, and the label `azure.workload.identity/use: "true"`, and no `AZURE_CLIENT_SECRET` |

   The other steps are as before.  A stack without Polaris, eg `azure-keycloak`, needs no credential and is deployed in one step, as it is with the flag off, `deploy_<stack>_stack`.  The credential can take a few minutes to take effect, which the deploy of the rest usually outlasts; if Polaris fails to get a
   token (error code `AADSTS70021`: no matching federated identity record), wait and restart it with `kubectl rollout restart deploy/polaris-deployment`.

3. **Check.**  The pod has no secret, but has a token:
   ```shell
   kubectl exec deploy/polaris-deployment -- env | grep AZURE_     # AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_FEDERATED_TOKEN_FILE, no AZURE_CLIENT_SECRET
   afon-cli.sh azure-identity [<stack-id>]                         # whether the app has the credential of the cluster, and what it is
   ```
   then run the Azure smoke test, which writes to the container through Polaris.

**Trying it on an env that uses the secret.**  The same app can hold both.  Skip the admin's steps, keep the IDs and the secret, set `AZURE_WORKLOAD_IDENTITY=true`, run `afon-cli.sh azure-identity true <stack-id>` for the cluster (the setup does it),
then plan and apply the stack, and check as in step 3: the secret is then ignored, with a warning.  Set the flag back to `false` to return to the secret.

**Notes**
- `issue-cert.sh` with Let's Encrypt gives acme.sh the token of the deployer's `az` login for Azure DNS, so the deployer needs to write DNS records in the resource group, eg as its Contributor.
