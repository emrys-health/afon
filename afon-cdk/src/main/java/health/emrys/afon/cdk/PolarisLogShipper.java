package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static java.lang.String.format;

/**
 * Ships Polaris' console logs to the object storage of the cloud with a Fluent Bit DaemonSet, so that they outlive
 * the pod and the cluster.
 * <p>
 * Each node's Fluent Bit tails the log files the kubelet writes for the Polaris containers on that node, adds the
 * pod's metadata, and uploads gzipped JSON lines under {@code polaris/<cluster>/<yyyy>/<mm>/<dd>/}.  Subclasses give
 * Fluent Bit its access to the storage, and the output that writes to it.
 * <p>
 * Polaris should depend on the {@link Release} returned by {@link #deploy}: on destroy, Polaris then goes first,
 * while Fluent Bit is still there to ship its last lines, which it uploads when it is itself stopped.
 */
public abstract class PolarisLogShipper {

    protected static final String NAMESPACE       = "kube-system";
    protected static final String SERVICE_ACCOUNT = "fluent-bit-polaris";
    /** How long Fluent Bit gets, once stopped, to upload what it has buffered. */
    protected static final int    GRACE_SECONDS   = 30;

    /** Polaris' logs of the cluster go under this prefix, so that the stacks sharing storage keep theirs apart. */
    protected final String prefix;

    protected PolarisLogShipper(String cluster) {
        this.prefix = "polaris/" + cluster;
    }

    public Release deploy(Construct scope, List<@NonNull ITerraformDependable> clusterDependOn) {
        List<ITerraformDependable> dependsOn = new ArrayList<>(clusterDependOn);
        dependsOn.addAll(prerequisites(scope, clusterDependOn));
        return Release.Builder
            .create(scope, "polarisLogShipper")
            .name("fluent-bit-polaris")
            .chart(vendoredChart(scope, "fluentBitChart", "fluent-bit-0.58.3.tgz"))
            .namespace(NAMESPACE)
            .values(List.of(values()))
            .timeout(600)
            .atomic(true)
            .dependsOn(dependsOn)
            .build();
    }

    /**
     * Creates what Fluent Bit needs before it starts, eg its access to the storage.  Called by {@link #deploy} before
     * {@link #values()}, which may then refer to what was created.
     *
     * @return what the release must depend on, besides the cluster
     */
    protected abstract List<ITerraformDependable> prerequisites(Construct scope,
                                                                List<@NonNull ITerraformDependable> clusterDependOn);

    /** @return the YAML under {@code serviceAccount}, after its {@code create} and {@code name}, if any */
    protected String serviceAccountValues() {
        return "";
    }

    /** @return top level YAML values of the chart, besides those of the service account and the config, if any */
    protected String extraValues() {
        return "";
    }

    /** @return the {@code [OUTPUT]} section of the Fluent Bit config, which writes the {@code polaris.*} records */
    protected abstract String output();

    /**
     * Values for the {@code fluent/fluent-bit} chart.  Only the Polaris containers' files are tailed: the kubelet
     * names them {@code <pod>_<namespace>_<container>-<id>.log}, and the Polaris pods are those of
     * {@code polaris-deployment}.  The tail positions and the not yet uploaded logs are kept on the node, under
     * {@code /var/log}, so that a restarted Fluent Bit neither ships lines twice nor loses them.  Files are read from
     * the start when first seen, else the lines Polaris logged before Fluent Bit started would be skipped.
     * <p>
     * The chart renders the config through {@code tpl}, hence {@code {{ .Values.metricsPort }}}.
     */
    public String values() {
        return format("""
            serviceAccount:
              create: true
              name: %s
            %s\
            terminationGracePeriodSeconds: %d
            %s\
            config:
              service: |
                [SERVICE]
                    Daemon Off
                    Flush 1
                    Grace %d
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
            %s""",
                      SERVICE_ACCOUNT,
                      indent(serviceAccountValues(), 2),
                      GRACE_SECONDS,
                      indent(extraValues(), 0),
                      GRACE_SECONDS,
                      indent(output(), 4));
    }

    private static String indent(String yaml, int spaces) {
        return yaml.isEmpty() ? "" : yaml.stripIndent().indent(spaces);
    }
}
