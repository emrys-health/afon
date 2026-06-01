package health.emrys.afon.cdk;

import static java.lang.String.format;

/**
 * {@link TlsCertStore} backed by an Azure Key Vault. The vault is referenced by
 * name, not created here: it must outlive the cluster, so it has to live in a
 * resource group that is not torn down with the AKS cluster. The principal
 * running {@code az} needs get/set permission on the vault's secrets.
 *
 * <p>The cert blob is stored as a single-line secret value, so Key Vault's
 * {@code -o tsv} retrieval round-trips it without newline mangling.
 */
public class AzureKeyVaultCertStore implements TlsCertStore {

    private final String vaultName;

    public AzureKeyVaultCertStore(String vaultName) {
        this.vaultName = vaultName;
    }

    @Override
    public String uploadCommand(String key, String localFile) {
        return format(
            "az keyvault secret set --vault-name %1$s --name %2$s --file %3$s --output none",
            vaultName, key, localFile);
    }

    @Override
    public String downloadCommand(String key, String localFile) {
        return format(
            "az keyvault secret show --vault-name %1$s --name %2$s --query value -o tsv > %3$s 2>/dev/null || rm -f %3$s",
            vaultName, key, localFile);
    }
}
