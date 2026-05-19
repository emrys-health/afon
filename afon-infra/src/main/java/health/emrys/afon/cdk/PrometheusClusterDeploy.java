package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class PrometheusClusterDeploy extends SparkClusterDeployment {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PrometheusClusterDeploy.class);

    public PrometheusClusterDeploy(@NotNull Construct scope,
                                   @NotNull SupportedCloudsEnum id,
                                   Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> dependablesAndLoadBalancers = new ArrayList<>();
        dependablesAndLoadBalancers.addAll(loadBalancers);
        dependablesAndLoadBalancers.addAll(cloudProvider.getDependables());
        deployPrometheus(dependablesAndLoadBalancers);
        Release loki = deployLoki(dependablesAndLoadBalancers);
        deployPromtail(dependablesAndLoadBalancers, loki);
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

    private void deployPrometheus(List<@NotNull ITerraformDependable> eksDependOn) {
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
                grafanaLokiDatasourceValues()
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
     * ships Loki 3.x). Persistence is disabled — this is a dev cluster, lost
     * logs on pod restart are an acceptable trade for one less PVC to manage.
     *
     * <p>The companion log shipper is installed by
     * {@link #deployPromtail(List, Release)}; logs are queried in the same
     * Grafana that kube-prometheus-stack already deploys, wired up by
     * {@link #grafanaLokiDatasourceValues()}.
     */
    private Release deployLoki(List<@NotNull ITerraformDependable> eksDependOn) {
        return Release.Builder
            .create(this, "lokiRelease")
            .name("loki")
            .chart("loki")
            .repository(GRAFANA_HELM_REPO)
            .version("6.16.0")
            .namespace("default")
            .values(List.of(lokiValues()))
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
    private void deployPromtail(List<@NotNull ITerraformDependable> eksDependOn, Release loki) {
        List<ITerraformDependable> deps = new ArrayList<>(eksDependOn);
        deps.add(loki);
        Release.Builder
            .create(this, "promtailRelease")
            .name("promtail")
            .chart("promtail")
            .repository(GRAFANA_HELM_REPO)
            .version("6.16.5")
            .namespace("default")
            .values(List.of(promtailValues()))
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
    static String lokiValues() {
        return """
            deploymentMode: SingleBinary
            loki:
              auth_enabled: false
              commonConfig:
                replication_factor: 1
              storage:
                type: filesystem
              schemaConfig:
                configs:
                  - from: 2024-04-01
                    store: tsdb
                    object_store: filesystem
                    schema: v13
                    index:
                      prefix: loki_index_
                      period: 24h
            singleBinary:
              replicas: 1
              persistence:
                enabled: false
              # Loki runs as UID 10001 (chart default) and the chart only mounts
              # a volume at /var/loki when persistence is enabled. With
              # persistence off, the loki user has no writable directory for
              # the ruler-storage module's mkdir — even with the rootfs
              # writable, /var is root-owned. Mount a per-pod emptyDir there;
              # the chart's podSecurityContext.fsGroup makes it writable.
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
            """;
    }

    /**
     * Values for the {@code grafana/promtail} chart. The
     * {@code extraRelabelConfigs} entry is appended to Promtail's default
     * {@code kubernetes-pods} scrape job; {@code action: keep} drops every
     * pod whose namespace isn't {@code default}.
     */
    static String promtailValues() {
        return """
            config:
              clients:
                - url: http://loki.default.svc.cluster.local:3100/loki/api/v1/push
              snippets:
                extraRelabelConfigs:
                  - source_labels: [__meta_kubernetes_namespace]
                    action: keep
                    regex: default
            """;
    }

    /**
     * Registers the Loki service as an additional Grafana datasource on the
     * kube-prometheus-stack release. {@code access: proxy} routes queries
     * through the Grafana pod, so we don't need to expose Loki externally.
     */
    static String grafanaLokiDatasourceValues() {
        return """
            grafana:
              additionalDataSources:
                - name: Loki
                  type: loki
                  access: proxy
                  url: http://loki.default.svc.cluster.local:3100
                  isDefault: false
                  editable: false
            """;
    }

    public static void main(String[] args) {
        logger.info("Creating JSON for Prometheus");
        final App app = new App();
        try {
            new PrometheusClusterDeploy(app, AwsKubernetesPolaris.ID, new AfonPropertiesLoader().getProperties());
            app.synth();
        } catch (RuntimeException e) {
            logAndThrow(e);
        }
    }
}
