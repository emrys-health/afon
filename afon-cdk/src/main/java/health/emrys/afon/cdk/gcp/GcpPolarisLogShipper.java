package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import health.emrys.afon.cdk.PolarisLogShipper;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static java.lang.String.format;

/**
 * Ships Polaris' console logs to GCS, under {@code gs://<bucket>/polaris/<cluster>/<yyyy>/<mm>/<dd>/}.  Fluent Bit has no GCS output
 * of its own, so it goes through GCS' S3 compatible API with its {@code s3} output, as Gitea does, which only takes an HMAC key: the
 * key of the service account, in a secret, which reaches the config as the environment variables that Fluent Bit reads its S3
 * credentials from, so that it is not in the chart's values.
 */
public class GcpPolarisLogShipper extends PolarisLogShipper {

    static final String SECRET_NAME     = "fluent-bit-polaris-gcs";
    static final String ACCESS_ID_KEY   = "accessId";
    static final String SECRET_KEY      = "secret";
    static final String GCS_ENDPOINT    = "https://storage.googleapis.com";

    private final String bucket;
    private final String region;
    private final String hmacAccessId;
    private final String hmacSecret;

    public GcpPolarisLogShipper(GcpCloudProvider cloudProvider, String bucket, String region, String hmacAccessId, String hmacSecret) {
        this(cloudProvider.clusterName(), bucket, region, hmacAccessId, hmacSecret);
    }

    GcpPolarisLogShipper(String cluster, String bucket, String region, String hmacAccessId, String hmacSecret) {
        super(cluster);
        this.bucket       = bucket;
        this.region       = region;
        this.hmacAccessId = hmacAccessId;
        this.hmacSecret   = hmacSecret;
    }

    @Override
    protected List<ITerraformDependable> prerequisites(Construct scope,
                                                       List<@NonNull ITerraformDependable> clusterDependOn) {
        return List.of(Secret.Builder
                           .create(scope, "polarisLogShipperSecret")
                           .dependsOn(clusterDependOn)
                           .metadata(SecretMetadata.builder().name(SECRET_NAME).namespace(NAMESPACE).build())
                           .data(Map.of(ACCESS_ID_KEY, hmacAccessId, SECRET_KEY, hmacSecret))
                           .type("Opaque")
                           .build());
    }

    @Override
    protected String extraValues() {
        return format("""
            env:
              - name: AWS_ACCESS_KEY_ID
                valueFrom:
                  secretKeyRef:
                    name: %1$s
                    key: %2$s
              - name: AWS_SECRET_ACCESS_KEY
                valueFrom:
                  secretKeyRef:
                    name: %1$s
                    key: %3$s
            """, SECRET_NAME, ACCESS_ID_KEY, SECRET_KEY);
    }

    @Override
    protected String output() {
        return format("""
            [OUTPUT]
                Name s3
                Match polaris.*
                bucket %s
                region %s
                endpoint %s
                use_put_object On
                compression gzip
                total_file_size 50M
                upload_timeout 30m
                store_dir /var/log/fluent-bit-polaris/gcs
                s3_key_format /%s/%%Y/%%m/%%d/%%H%%M%%S-$UUID.json.gz
            """, bucket, region, GCS_ENDPOINT, prefix);
    }
}
