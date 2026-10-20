package health.emrys.afon.cdk.components;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.LogStorage;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;

/**
 * The monitoring of the cluster: kube-prometheus-stack for the metrics, with the Cilium ServiceMonitors, and Loki, with
 * Promtail shipping the logs to it, which keeps them in the cloud's object storage, see
 * {@link CloudPlatform#logStorage()}.  It waits for the {@link CrdCleanupComponent}, so
 * that on destroy it is uninstalled before the CRDs it needs are gone.
 */
public class MonitoringComponent implements StackComponent {

    public static final String MONITORING_NAMESPACE = "default";

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(CrdCleanupComponent.class);
    }


    @Override
    public void deploy(StackContext context) {
        CloudPlatform    platform = context.platform();
        TerraformStack   stack    = context.stack();
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        // kube-prometheus-stack, Loki and Promtail all create Services (11 of them, one per Service in the chart),
        // which fire the cloud's mutating webhooks, if it has any, rejecting a Service until the webhook is serving.
        // The service dependables are what the platform needs waiting for. Same reasoning as Jupyter's and
        // Keycloak's Services.
        dependsOn.addAll(platform.getServiceDependables());
        // On destroy, the CRD cleanup deletes the monitoring.coreos.com, monitoring.grafana.com and
        // cilium.io CRDs (see CrdCleanup#GROUPS). kube-prometheus-stack's release
        // manifest contains Prometheus/Alertmanager/ServiceMonitor/PrometheusRule objects, and Helm
        // cannot uninstall objects whose CRD is gone ("failed to delete release"). Depending on the
        // cleanup makes these releases be uninstalled before it runs.
        dependsOn.add(context.require(CrdCleanupComponent.class).cleanup());
        // The Loki tenant is the name of the cluster, which prefixes the logs in the object storage
        String lokiTenant = platform.clusterName();
        LogStorage logStorage = platform.logStorage();
        deployPrometheus(stack, lokiTenant, dependsOn);
        Release loki = deployLoki(stack, dependsOn, logStorage);
        deployPromtail(stack, lokiTenant, logStorage, dependsOn, loki);
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

    private void deployPrometheus(TerraformStack stack, String lokiTenant, List<@NonNull ITerraformDependable> dependsOn) {
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
            .create(stack, "prometheusRelease")
            .name("kube-prometheus-stack")
            .chart(vendoredChart(stack, "prometheusChart", "kube-prometheus-stack-51.8.0.tgz"))

            // Reference the namespace created above
            .namespace(MONITORING_NAMESPACE)
            .set(chartOverrides)
            // ServiceMonitors for the Cilium components are passed as values so
            // the chart renders them itself: same release as the CRDs, in the
            // right order, fully tracked by Terraform.
            // The Loki datasource is wired into Grafana via the same values
            // stream so log queries (LogQL) sit next to metric queries (PromQL)
            // in the same Grafana instance kube-prometheus-stack already ships.
            .values(List.of(
                additionalServiceMonitorsValues(CILIUM_MONITORS),
                grafanaLokiDatasourceValues(lokiTenant)
                          ))
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)

            // Ensure the release depends on the namespace being created first
            .dependsOn(dependsOn)
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

    /**
     * Installs the current {@code grafana/loki} chart (single-binary mode,
     * ships Loki 3.x) configured to push chunks and the index to the
     * {@link LogStorage} of the platform, whose bucket, region, and credentials the platform reads from its config.
     *
     * <p>The companion log shipper is installed by
     * {@link #deployPromtail}; logs are queried in the same
     * Grafana that kube-prometheus-stack already deploys, wired up by
     * {@link #grafanaLokiDatasourceValues(String)}.
     */
    private Release deployLoki(TerraformStack stack, List<@NonNull ITerraformDependable> dependsOn, LogStorage logStorage) {
        return Release.Builder
            .create(stack, "lokiRelease")
            .name("loki")
            .chart(vendoredChart(stack, "lokiChart", "loki-6.16.0.tgz"))
            .namespace(MONITORING_NAMESPACE)
            .values(List.of(lokiValues(logStorage)))
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)
            .dependsOn(dependsOn)
            .build();
    }

    /**
     * Installs the current {@code grafana/promtail} chart as a DaemonSet that
     * tails {@code /var/log/pods/} on every node. The {@code action: keep}
     * relabel rule on {@code __meta_kubernetes_namespace} scopes the shipped
     * lines to the {@code default} namespace, so Loki only stores what the
     * user asked for.
     */
    private void deployPromtail(TerraformStack stack, String lokiTenant, LogStorage logStorage, List<@NonNull ITerraformDependable> dependsOn, Release loki) {
        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(loki);
        Release.Builder
            .create(stack, "promtailRelease")
            .name("promtail")
            .chart(vendoredChart(stack, "promtailChart", "promtail-6.16.5.tgz"))
            .namespace(MONITORING_NAMESPACE)
            .values(List.of(promtailValues(lokiTenant, logStorage.serviceName())))
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
    static String lokiValues(LogStorage storage) {
        // the storage config, indented to sit under the `storage:` key below
        String storageBlock = storage.storageYaml().stripTrailing().indent(4).stripTrailing();
        return String.format("""
            deploymentMode: SingleBinary
            loki:
              # Multi-tenancy on: every object key is prefixed with the tenant
              # ID. Promtail and Grafana send the cluster name as the tenant (see
              # promtailValues / grafanaLokiDatasourceValues), so the %2$s layout
              # becomes <bucket>/<cluster-name>/... instead of the auth-off
              # default of <bucket>/fake/...
              auth_enabled: true
              commonConfig:
                replication_factor: 1
              # All long-term data (chunks, index, ruler rules) is shipped to
              # %2$s. Credentials come from afon.properties / secret.properties via
              # AfonProperty — the rendered values land in the local tofu
              # state and the synthesized stack dir under $AFON_HOME/cdktf
              # (both gitignored), not in git history.
              storage:
            %1$s
              schemaConfig:
                configs:
                  - from: 2024-04-01
                    store: tsdb
                    object_store: %3$s
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
              # Persistence stays off — durable state is in %2$s. The emptyDir
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
            """, storageBlock, storage.serviceName(), storage.lokiType());
    }

    /**
     * Values for the {@code grafana/promtail} chart. The
     * {@code extraRelabelConfigs} entry is appended to Promtail's default
     * {@code kubernetes-pods} scrape job; {@code action: keep} drops every
     * pod whose namespace isn't {@code default}.
     */
    static String promtailValues(String tenant, String storageService) {
        return String.format("""
            config:
              clients:
                # tenant_id sets the X-Scope-OrgID header on every push, which
                # is the tenant Loki uses as the top-level %2$s directory.
                - url: http://loki.default.svc.cluster.local:3100/loki/api/v1/push
                  tenant_id: %1$s
              snippets:
                extraRelabelConfigs:
                  - source_labels: [__meta_kubernetes_namespace]
                    action: keep
                    regex: default
            """, tenant, storageService);
    }

    /**
     * Registers the Loki service as an additional Grafana datasource on the
     * kube-prometheus-stack release. {@code access: proxy} routes queries
     * through the Grafana pod, so we don't need to expose Loki externally.
     */
    static String grafanaLokiDatasourceValues(String tenant) {
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
            """, tenant);
    }
}
