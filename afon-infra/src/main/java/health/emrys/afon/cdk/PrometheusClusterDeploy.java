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
        deployPrometheus(dependablesAndLoadBalancers);
    }


    /**
     * Create an SSH tunnel with:
     * kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090
     * and run:
     * curl -g 'http://localhost:9090/api/v1/query?query=sum(rate(container_network_receive_bytes_total[5m]))' | jq
     * to see how much data has been ingressed
     *
     * @param eksDependOn
     */
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
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)

            // Ensure the release depends on the namespace being created first
            .dependsOn(eksDependOn)

            // ServiceMonitors for Cilium agent + operator. Applied via kubectl
            // (not kubernetes_manifest) because the monitoring.coreos.com CRDs are
            // installed by *this* helm release — kubernetes_manifest would call
            // the API server at plan time and fail. atomic=true above guarantees
            // the CRDs are ready by the time the provisioner fires.
            // The `release: kube-prometheus-stack` label is what the chart's
            // default serviceMonitorSelector matches against, so the operator
            // picks these up automatically.
            .provisioners(CdkUtils.uponCreateRun(CILIUM_SERVICE_MONITORS_YAML))
            .build();
    }

    private static final String CILIUM_SERVICE_MONITORS_YAML = """
        kubectl apply -f - <<'YAML'
        apiVersion: monitoring.coreos.com/v1
        kind: ServiceMonitor
        metadata:
          name: cilium-agent
          namespace: kube-system
          labels:
            release: kube-prometheus-stack
        spec:
          selector:
            matchLabels:
              app.kubernetes.io/name: cilium-agent
          namespaceSelector:
            matchNames:
              - kube-system
          endpoints:
            - port: metrics
              interval: 30s
              honorLabels: true
              path: /metrics
        ---
        apiVersion: monitoring.coreos.com/v1
        kind: ServiceMonitor
        metadata:
          name: cilium-operator
          namespace: kube-system
          labels:
            release: kube-prometheus-stack
        spec:
          selector:
            matchLabels:
              app.kubernetes.io/name: cilium-operator
          namespaceSelector:
            matchNames:
              - kube-system
          endpoints:
            - port: metrics
              interval: 30s
              honorLabels: true
              path: /metrics
        ---
        apiVersion: monitoring.coreos.com/v1
        kind: ServiceMonitor
        metadata:
          name: cilium-envoy
          namespace: kube-system
          labels:
            release: kube-prometheus-stack
        spec:
          selector:
            matchLabels:
              app.kubernetes.io/name: cilium-envoy
          namespaceSelector:
            matchNames:
              - kube-system
          endpoints:
            - port: envoy-metrics
              interval: 30s
              honorLabels: true
              path: /metrics
        ---
        apiVersion: monitoring.coreos.com/v1
        kind: ServiceMonitor
        metadata:
          name: hubble
          namespace: kube-system
          labels:
            release: kube-prometheus-stack
        spec:
          selector:
            matchLabels:
              k8s-app: hubble
          namespaceSelector:
            matchNames:
              - kube-system
          endpoints:
            - port: hubble-metrics
              interval: 30s
              honorLabels: true
              path: /metrics
        YAML""";

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
