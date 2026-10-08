package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.helm.release.Release;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static java.lang.String.format;

/**
 * Ships Polaris' console logs to S3 with a Fluent Bit DaemonSet, so that they outlive the pod and the cluster.
 * <p>
 * Each node's Fluent Bit tails the log files the kubelet writes for the Polaris containers on that node, adds the
 * pod's metadata, and uploads gzipped JSON lines to {@code s3://<bucket>/polaris/<cluster>/<yyyy>/<mm>/<dd>/}, at
 * least every minute.  It assumes its role via IRSA, which may only write under that prefix.
 * <p>
 * Polaris should depend on the {@link Release} returned by {@link #deploy}: on destroy, Polaris then goes first,
 * while Fluent Bit is still there to ship its last lines, which it uploads when it is itself stopped.
 */
public class AwsPolarisLogShipper {

    static final String NAMESPACE       = "kube-system";
    static final String SERVICE_ACCOUNT = "fluent-bit-polaris";
    /** How long Fluent Bit gets, once stopped, to upload what it has buffered. */
    private static final int GRACE_SECONDS = 30;

    private AwsPolarisLogShipper() {
    }

    public static Release deploy(Construct scope,
                                 AwsCloudProvider cloudProvider,
                                 List<@NonNull ITerraformDependable> eksDependOn,
                                 String bucket,
                                 String region) {
        String prefix = keyPrefix(cloudProvider.getComponentNames().getCluster());
        IamRole role = cloudProvider.createIrsaRole(
            "polarisLogShipperRole",
            cloudProvider.getComponentNames().getPolarisLogShipperRoleName(),
            NAMESPACE,
            SERVICE_ACCOUNT,
            putObjectPolicy(bucket, prefix));
        List<ITerraformDependable> dependsOn = new ArrayList<>(eksDependOn);
        dependsOn.add(role);
        return Release.Builder
            .create(scope, "polarisLogShipper")
            .name("fluent-bit-polaris")
            .chart(vendoredChart(scope, "fluentBitChart", "fluent-bit-0.58.3.tgz"))
            .namespace(NAMESPACE)
            .values(List.of(values(bucket, region, prefix, role.getArn())))
            .timeout(600)
            .atomic(true)
            .dependsOn(dependsOn)
            .build();
    }

    /** Polaris' logs of a cluster go under this prefix, so that the stacks sharing a bucket keep theirs apart. */
    static String keyPrefix(String cluster) {
        return "polaris/" + cluster;
    }

    static String putObjectPolicy(String bucket, String prefix) {
        return format("""
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Action": "s3:PutObject",
                  "Resource": "arn:aws:s3:::%s/%s/*"
                }
              ]
            }""", bucket, prefix);
    }

    /**
     * Values for the {@code fluent/fluent-bit} chart.  Only the Polaris containers' files are tailed: the kubelet
     * names them {@code <pod>_<namespace>_<container>-<id>.log}, and the Polaris pods are those of
     * {@code polaris-deployment}.  The tail positions and the not yet uploaded logs are kept on the node, under
     * {@code /var/log}, so that a restarted Fluent Bit neither ships lines twice nor loses them.  Files are read from
     * the start when first seen, else the lines Polaris logged before Fluent Bit started would be skipped.
     * <p>
     * The chart renders the config through {@code tpl}, hence {@code {{ .Values.metricsPort }}}.
     */
    static String values(String bucket, String region, String prefix, String roleArn) {
        return format("""
            serviceAccount:
              create: true
              name: %5$s
              annotations:
                eks.amazonaws.com/role-arn: %4$s
            terminationGracePeriodSeconds: %6$d
            config:
              service: |
                [SERVICE]
                    Daemon Off
                    Flush 1
                    Grace %6$d
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
                    Name s3
                    Match polaris.*
                    bucket %1$s
                    region %2$s
                    use_put_object On
                    compression gzip
                    total_file_size 50M
                    upload_timeout 1m
                    store_dir /var/log/fluent-bit-polaris/s3
                    s3_key_format /%3$s/%%Y/%%m/%%d/%%H%%M%%S-$UUID.json.gz
            """, bucket, region, prefix, roleArn, SERVICE_ACCOUNT, GRACE_SECONDS);
    }
}
