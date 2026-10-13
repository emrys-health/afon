package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import health.emrys.afon.cdk.PolarisLogShipper;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

/**
 * Ships Polaris' console logs to Azure Blob Storage, as gzipped block blobs under
 * {@code <container>/polaris/<cluster>/<yyyy>/<mm>/<dd>/} of the storage account.  Fluent Bit's {@code azure_blob}
 * output only authenticates with a shared key or a SAS token, not a workload identity, so it is given the account key
 * of the connection string, in a secret, which reaches the config as an environment variable so that it is not in
 * the chart's values.
 */
public class AzurePolarisLogShipper extends PolarisLogShipper {

    static final String SECRET_NAME = "fluent-bit-polaris-azure";
    static final String SECRET_KEY  = "sharedKey";

    private final String storageAccount;
    private final String container;
    private final String sharedKey;

    public AzurePolarisLogShipper(AzureCloudProvider cloudProvider,
                                  String storageAccount,
                                  String container,
                                  String connectionString) {
        this(cloudProvider.clusterName(), storageAccount, container, connectionString);
    }

    AzurePolarisLogShipper(String cluster, String storageAccount, String container, String connectionString) {
        super(cluster);
        this.storageAccount = storageAccount;
        this.container      = container;
        this.sharedKey      = accountKey(connectionString);
    }

    /**
     * @return the {@code AccountKey} of a storage account connection string, ie
     * {@code DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...;EndpointSuffix=...}
     */
    static String accountKey(String connectionString) {
        return Arrays.stream(connectionString.split(";"))
                     .map(String::trim)
                     .filter(entry -> entry.startsWith("AccountKey="))
                     .map(entry -> entry.substring("AccountKey=".length()))
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException(
                         "The connection string has no AccountKey, which Fluent Bit needs to write Polaris' logs to the storage account"));
    }

    @Override
    protected List<ITerraformDependable> prerequisites(Construct scope,
                                                       List<@NonNull ITerraformDependable> clusterDependOn) {
        return List.of(Secret.Builder
                           .create(scope, "polarisLogShipperSecret")
                           .dependsOn(clusterDependOn)
                           .metadata(SecretMetadata.builder().name(SECRET_NAME).namespace(NAMESPACE).build())
                           .data(Map.of(SECRET_KEY, sharedKey))
                           .type("Opaque")
                           .build());
    }

    @Override
    protected String extraValues() {
        return format("""
            env:
              - name: AZURE_STORAGE_SHARED_KEY
                valueFrom:
                  secretKeyRef:
                    name: %s
                    key: %s
            """, SECRET_NAME, SECRET_KEY);
    }

    @Override
    protected String output() {
        return format("""
            [OUTPUT]
                Name azure_blob
                Match polaris.*
                account_name %s
                shared_key ${AZURE_STORAGE_SHARED_KEY}
                container_name %s
                auto_create_container Off
                tls On
                blob_type blockblob
                buffering_enabled On
                buffer_dir /var/log/fluent-bit-polaris/azure-blob
                database_file /var/log/fluent-bit-polaris/azure-blob.db
                compress_blob On
                upload_file_size 50M
                upload_timeout 30m
                path %s/%%Y/%%m/%%d/%%H%%M%%S-$UUID
            """, storageAccount, container, prefix);
    }
}
