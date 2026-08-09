package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonCloud.AWS;

public class AwsFullStack extends AwsTrinoStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsFullStack.class);

    public AwsFullStack(@NonNull Construct scope,
                        @NonNull AfonCloud id,
                        Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> dependablesAndLoadBalancers = new ArrayList<>();
        dependablesAndLoadBalancers.addAll(loadBalancers);
        dependablesAndLoadBalancers.addAll(cloudProvider.getDependables());
        // kube-prometheus-stack, Loki and Promtail all create Services, and the AWS LB
        // Controller's mservice.elbv2.k8s.aws webhook (failurePolicy=Fail) intercepts every
        // Service creation, not just type: LoadBalancer. Wait on the controller's Helm release
        // — installed atomically, so Helm blocks until its pods are Ready — otherwise the
        // webhook Service still has zero endpoints and the release fails with "no endpoints
        // available for service aws-load-balancer-webhook-service" (11 of them, one per
        // Service in the chart). Same reasoning as Jupyter's and Keycloak's Services.
        dependablesAndLoadBalancers.addAll(cloudProvider.getServiceDependables());
        deployPrometheus(dependablesAndLoadBalancers, properties);
        Release loki = deployLoki(dependablesAndLoadBalancers, properties);
        deployPromtail(dependablesAndLoadBalancers, loki, properties);
    }


    /**
     * Declarative description of a Service we want Prometheus to scrape.
     * Rendered into the chart's {@code prometheus.additionalServiceMonitors}
     * Helm values, so kube-prometheus-stack installs the ServiceMonitor as part
     * of its own release (CRDs and resources in the right order, drift
     * detection, automatic deletion on destroy — none of which the previous
     * kubectl-apply provisioner gave us).
     * Create an SSH tunnel with:
     * kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090
     * and run:
     * curl -g 'http://localhost:9090/api/v1/query?query=sum(rate(container_network_receive_bytes_total[5m]))' | jq
     * to see how much data has been ingressed
     */
    private record MonitoredService(String name,
                                    String selectorKey,
                                    String selectorValue,
                                    String portName) {}

    private static final List<MonitoredService> CILIUM_MONITORS = List.of(
        new MonitoredService("cilium-agent",    "app.kubernetes.io/name", "cilium-agent",    "metrics"),
        new MonitoredService("cilium-operator", "app.kubernetes.io/name", "cilium-operator", "metrics"),
        new MonitoredService("cilium-envoy",    "app.kubernetes.io/name", "cilium-envoy",    "envoy-metrics"),
        new MonitoredService("hubble",          "k8s-app",                "hubble",          "hubble-metrics"),
        new MonitoredService("hubble-relay",    "k8s-app",                "hubble-relay",    "metrics")
    );

    private void deployPrometheus(List<@NonNull ITerraformDependable> eksDependOn, Properties properties) {
        // Configuration Overrides for the Helm Chart
        // We use the ReleaseSet.builder() for the `set` block in Terraform
        final List<ReleaseSet> chartOverrides = List.of(
            ReleaseSet
                .builder()
                .name("grafana.persistence.enabled")
                .value("false") // Disable Grafana persistence for simplicity
                .build(),
            ReleaseSet
                .builder()
                .name("prometheus.prometheusSpec.storageRetention")
                .value("7d") // Keep metrics for 7 days
                .build()
                                                       );

        // Install the kube-prometheus-stack Helm chart
        Release.Builder
            .create(this, "prometheusRelease")
            .name("kube-prometheus-stack")
            .chart("kube-prometheus-stack")
            .repository("https://prometheus-community.github.io/helm-charts")
            .version("51.8.0") // Specify a version for consistency

            // Reference the namespace created above
            .namespace("default")
            .set(chartOverrides)
            // ServiceMonitors for the Cilium components are passed as values so
            // the chart renders them itself: same release as the CRDs, in the
            // right order, fully tracked by Terraform.
            // The Loki datasource is wired into Grafana via the same values
            // stream so log queries (LogQL) sit next to metric queries (PromQL)
            // in the same Grafana instance kube-prometheus-stack already ships.
            .values(List.of(
                additionalServiceMonitorsValues(CILIUM_MONITORS),
                grafanaLokiDatasourceValues(properties)
                          ))
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)

            // Ensure the release depends on the namespace being created first
            .dependsOn(eksDependOn)
            .build();
    }

    /**
     * Builds the {@code prometheus.additionalServiceMonitors} YAML fragment.
     * The chart adds {@code apiVersion}/{@code kind}/{@code metadata.name} and
     * its own {@code release: kube-prometheus-stack} label automatically, so
     * each entry only needs the spec-level fields.
     */
    static String additionalServiceMonitorsValues(List<MonitoredService> monitors) {
        StringBuilder sb = new StringBuilder("prometheus:\n  additionalServiceMonitors:\n");
        for (MonitoredService m : monitors) {
            sb.append(String.format(
                """
                    - name: %s%n
                      selector:%n
                        matchLabels:%n
                          %s: %s%n
                      namespaceSelector:%n
                        matchNames:%n
                          - kube-system%n
                      endpoints:%n
                        - port: %s%n
                          interval: 30s%n
                          honorLabels: true%n
                          path: /metrics%n
                """,
                m.name(), m.selectorKey(), m.selectorValue(), m.portName()));
        }
        return sb.toString();
    }

    private static final String GRAFANA_HELM_REPO = "https://grafana.github.io/helm-charts";

    /**
     * Installs the current {@code grafana/loki} chart (single-binary mode,
     * ships Loki 3.x) configured to push chunks and the index to S3. The
     * bucket, region, and IAM credentials are read from the
     * {@link Properties} passed to the {@link AwsFullStack}
     * constructor (keys {@code AWS_S3_BUCKET_NAME}, {@code AWS_REGION},
     * {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}).
     *
     * <p>The companion log shipper is installed by
     * {@link #deployPromtail(List, Release, Properties)}; logs are queried in the same
     * Grafana that kube-prometheus-stack already deploys, wired up by
     * {@link #grafanaLokiDatasourceValues(Properties)}.
     */
    private Release deployLoki(List<@NonNull ITerraformDependable> eksDependOn, Properties properties) {
        return Release.Builder
            .create(this, "lokiRelease")
            .name("loki")
            .chart("loki")
            .repository(GRAFANA_HELM_REPO)
            .version("6.16.0")
            .namespace("default")
            .values(List.of(lokiValues(properties)))
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)
            .dependsOn(eksDependOn)
            .build();
    }

    /**
     * Installs the current {@code grafana/promtail} chart as a DaemonSet that
     * tails {@code /var/log/pods/} on every node. The {@code action: keep}
     * relabel rule on {@code __meta_kubernetes_namespace} scopes the shipped
     * lines to the {@code default} namespace, so Loki only stores what the
     * user asked for.
     */
    private void deployPromtail(List<@NonNull ITerraformDependable> eksDependOn, Release loki, Properties properties) {
        List<ITerraformDependable> deps = new ArrayList<>(eksDependOn);
        deps.add(loki);
        Release.Builder
            .create(this, "promtailRelease")
            .name("promtail")
            .chart("promtail")
            .repository(GRAFANA_HELM_REPO)
            .version("6.16.5")
            .namespace("default")
            .values(List.of(promtailValues(properties)))
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)
            .dependsOn(deps)
            .build();
    }

    /**
     * Minimal single-binary values for the {@code grafana/loki} v6 chart.
     * Filesystem storage + {@code replication_factor: 1} keep it self-contained;
     * the read/write/backend replica counts are zeroed because deploymentMode
     * SingleBinary is mutually exclusive with the scalable/microservices modes.
     */
    static String lokiValues(Properties properties) {
        String bucket    = AfonProperties.AWS_S3_BUCKET_NAME.getValueFrom(properties);
        String region    = AfonProperties.AWS_REGION.getValueFrom(properties);
        String accessKey = AfonProperties.AWS_ACCESS_KEY_ID.getValueFrom(properties);
        String secretKey = AfonProperties.AWS_SECRET_ACCESS_KEY.getValueFrom(properties);
        return String.format("""
            deploymentMode: SingleBinary
            loki:
              # Multi-tenancy on: every object key is prefixed with the tenant
              # ID. Promtail and Grafana send CLUSTER_NAME as the tenant (see
              # promtailValues / grafanaLokiDatasourceValues), so the S3 layout
              # becomes <bucket>/<CLUSTER_NAME>/... instead of the auth-off
              # default of <bucket>/fake/...
              auth_enabled: true
              commonConfig:
                replication_factor: 1
              # All long-term data (chunks, index, ruler rules) is shipped to
              # S3. Credentials come from afon.properties / secret.properties via
              # AfonProperties — the rendered values land in the local tofu
              # state and cdktf.out (both gitignored), not in git history.
              storage:
                type: s3
                bucketNames:
                  chunks: %1$s
                  ruler: %1$s
                  admin: %1$s
                s3:
                  region: %2$s
                  endpoint: ""
                  accessKeyId: %3$s
                  secretAccessKey: %4$s
                  s3ForcePathStyle: false
                  insecure: false
              schemaConfig:
                configs:
                  - from: 2024-04-01
                    store: tsdb
                    object_store: s3
                    schema: v13
                    index:
                      prefix: loki_index_
                      period: 24h
              # Enables the /loki/api/v1/index/volume endpoint that backs
              # Grafana's log-volume histogram. Without it Grafana reports
              # "Log volume has not been configured."
              limits_config:
                volume_enabled: true
            singleBinary:
              replicas: 1
              # Persistence stays off — durable state is in S3. The emptyDir
              # below covers Loki's local working paths (WAL, in-memory index
              # cache, compactor scratch), which the chart's default
              # readOnlyRootFilesystem=true would otherwise block.
              persistence:
                enabled: false
              extraVolumes:
                - name: loki-data
                  emptyDir: {}
              extraVolumeMounts:
                - name: loki-data
                  mountPath: /var/loki
            read:
              replicas: 0
            write:
              replicas: 0
            backend:
              replicas: 0
            chunksCache:
              enabled: false
            resultsCache:
              enabled: false
            test:
              enabled: false
            lokiCanary:
              enabled: false
            gateway:
              enabled: false
            """, bucket, region, accessKey, secretKey);
    }

    /**
     * Values for the {@code grafana/promtail} chart. The
     * {@code extraRelabelConfigs} entry is appended to Promtail's default
     * {@code kubernetes-pods} scrape job; {@code action: keep} drops every
     * pod whose namespace isn't {@code default}.
     */
    static String promtailValues(Properties properties) {
        String clusterName = AfonProperties.CLUSTER_NAME.getValueFrom(properties);
        return String.format("""
            config:
              clients:
                # tenant_id sets the X-Scope-OrgID header on every push, which
                # is the tenant Loki uses as the top-level S3 directory.
                - url: http://loki.default.svc.cluster.local:3100/loki/api/v1/push
                  tenant_id: %s
              snippets:
                extraRelabelConfigs:
                  - source_labels: [__meta_kubernetes_namespace]
                    action: keep
                    regex: default
            """, clusterName);
    }

    /**
     * Registers the Loki service as an additional Grafana datasource on the
     * kube-prometheus-stack release. {@code access: proxy} routes queries
     * through the Grafana pod, so we don't need to expose Loki externally.
     */
    static String grafanaLokiDatasourceValues(Properties properties) {
        String clusterName = AfonProperties.CLUSTER_NAME.getValueFrom(properties);
        return String.format("""
            grafana:
              additionalDataSources:
                - name: Loki
                  type: loki
                  access: proxy
                  url: http://loki.default.svc.cluster.local:3100
                  isDefault: false
                  editable: false
                  # Loki has auth_enabled: true, so reads must carry the tenant
                  # header too — otherwise Grafana queries a different tenant
                  # than Promtail writes to and returns no logs.
                  jsonData:
                    httpHeaderName1: X-Scope-OrgID
                  secureJsonData:
                    httpHeaderValue1: %s
            """, clusterName);
    }

    public static void main(String[] args) {
        logger.info("Creating full stack");
        final App app = new App();
        try {
            new AwsFullStack(app, AWS, new FilePropertyProvider().getProperties());
            app.synth();
        } catch (RuntimeException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
    }
}
