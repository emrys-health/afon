package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static java.lang.String.format;

/**
 * Ships Polaris' console logs to Azure Blob Storage with a Fluent Bit DaemonSet, so that they outlive the pod and the
 * cluster.
 * <p>
 * Each node's Fluent Bit tails the log files the kubelet writes for the Polaris containers on that node, adds the
 * pod's metadata, and uploads gzipped JSON lines as block blobs under
 * {@code <container>/polaris/<cluster>/<yyyy>/<mm>/<dd>/} of the storage account.  Fluent Bit's {@code azure_blob}
 * output only authenticates with a shared key or a SAS token, not a workload identity, so it is given the account key
 * of the connection string, in a secret.
 * <p>
 * Polaris should depend on the {@link Release} returned by {@link #deploy}: on destroy, Polaris then goes first,
 * while Fluent Bit is still there to ship its last lines, which it uploads when it is itself stopped.
 */
public class AzurePolarisLogShipper {

    static final String NAMESPACE       = "kube-system";
    static final String SERVICE_ACCOUNT = "fluent-bit-polaris";
    static final String SECRET_NAME     = "fluent-bit-polaris-azure";
    static final String SECRET_KEY      = "sharedKey";
    /** How long Fluent Bit gets, once stopped, to upload what it has buffered. */
    private static final int GRACE_SECONDS = 30;

    private AzurePolarisLogShipper() {
    }

    public static Release deploy(Construct scope,
                                 AzureCloudProvider cloudProvider,
                                 List<@NonNull ITerraformDependable> aksDependOn,
                                 String storageAccount,
                                 String container,
                                 String connectionString) {
        String prefix = keyPrefix(cloudProvider.clusterName());
        Secret secret = Secret.Builder
            .create(scope, "polarisLogShipperSecret")
            .dependsOn(aksDependOn)
            .metadata(SecretMetadata.builder().name(SECRET_NAME).namespace(NAMESPACE).build())
            .data(Map.of(SECRET_KEY, accountKey(connectionString)))
            .type("Opaque")
            .build();
        List<ITerraformDependable> dependsOn = new ArrayList<>(aksDependOn);
        dependsOn.add(secret);
        return Release.Builder
            .create(scope, "polarisLogShipper")
            .name("fluent-bit-polaris")
            .chart(vendoredChart(scope, "fluentBitChart", "fluent-bit-0.58.3.tgz"))
            .namespace(NAMESPACE)
            .values(List.of(values(storageAccount, container, prefix)))
            .timeout(600)
            .atomic(true)
            .dependsOn(dependsOn)
            .build();
    }

    /** Polaris' logs of a cluster go under this prefix, so that the stacks sharing a container keep theirs apart. */
    static String keyPrefix(String cluster) {
        return "polaris/" + cluster;
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

    /**
     * Values for the {@code fluent/fluent-bit} chart.  Only the Polaris containers' files are tailed: the kubelet
     * names them {@code <pod>_<namespace>_<container>-<id>.log}, and the Polaris pods are those of
     * {@code polaris-deployment}.  The tail positions and the not yet uploaded logs are kept on the node, under
     * {@code /var/log}, so that a restarted Fluent Bit neither ships lines twice nor loses them.  Files are read from
     * the start when first seen, else the lines Polaris logged before Fluent Bit started would be skipped.
     * <p>
     * The shared key reaches the config as an environment variable, so that it is not in the chart's values.  The
     * chart renders the config through {@code tpl}, hence {@code {{ .Values.metricsPort }}}.
     */
    static String values(String storageAccount, String container, String prefix) {
        return format("""
            serviceAccount:
              create: true
              name: %4$s
            terminationGracePeriodSeconds: %7$d
            env:
              - name: AZURE_STORAGE_SHARED_KEY
                valueFrom:
                  secretKeyRef:
                    name: %5$s
                    key: %6$s
            config:
              service: |
                [SERVICE]
                    Daemon Off
                    Flush 1
                    Grace %7$d
                    Log_Level info
                    Parsers_File /fluent-bit/etc/parsers.conf
                    HTTP_Server On
                    HTTP_Listen 0.0.0.0
                    HTTP_Port {{ .Values.metricsPort }}
                    Health_Check On
              inputs: |
                [INPUT]
                    Name tail
                    Path /var/log/containers/polaris-deployment-*_default_polaris-*.log
                    multiline.parser cri
                    Tag polaris.*
                    DB /var/log/fluent-bit-polaris-tail.db
                    Read_from_Head On
                    Mem_Buf_Limit 5MB
                    Skip_Long_Lines On
              filters: |
                [FILTER]
                    Name kubernetes
                    Match polaris.*
                    Kube_Tag_Prefix polaris.var.log.containers.
                    Merge_Log Off
                    Labels Off
                    Annotations Off
              outputs: |
                [OUTPUT]
                    Name azure_blob
                    Match polaris.*
                    account_name %1$s
                    shared_key ${AZURE_STORAGE_SHARED_KEY}
                    container_name %2$s
                    auto_create_container Off
                    tls On
                    blob_type blockblob
                    buffering_enabled On
                    buffer_dir /var/log/fluent-bit-polaris/azure-blob
                    database_file /var/log/fluent-bit-polaris/azure-blob.db
                    compress_blob On
                    upload_file_size 50M
                    upload_timeout 30m
                    path %3$s/%%Y/%%m/%%d/%%H%%M%%S-$UUID
            """, storageAccount, container, prefix, SERVICE_ACCOUNT, SECRET_NAME, SECRET_KEY, GRACE_SECONDS);
    }
}
