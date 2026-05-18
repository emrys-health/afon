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
            .values(List.of(additionalServiceMonitorsValues(CILIUM_MONITORS)))
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
