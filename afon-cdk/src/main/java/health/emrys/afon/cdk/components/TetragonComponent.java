package health.emrys.afon.cdk.components;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.configs.JupyterHubConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;

/**
 * Tetragon, the eBPF runtime observability of Cilium, watching what runs in the notebook pods that JupyterHub's
 * KubeSpawner spawns, ie where user code runs: every process they exec, and its exit, with the pod, container, image,
 * binary and arguments.
 * <p>
 * Its export is restricted to the pods carrying {@link JupyterHubConfig#USER_POD_LABELS}, and its {@code export-stdout}
 * sidecar prints the events as JSON lines, which the Promtail of the {@link MonitoringComponent} ships to Loki, as it
 * does the logs of every pod of the {@code default} namespace.  In Grafana they are queried with, eg
 * <pre>
 * {app="tetragon", container="export-stdout"} | json | process_exec_process_pod_name=~"jupyter-.*"
 * </pre>
 * The Tetragon operator registers the {@code cilium.io} CRDs of Tetragon at runtime, which the {@link CrdCleanupComponent}
 * removes on destroy: Tetragon depends on the cleanup, so that it is uninstalled first, rather than its operator
 * registering them again behind the cleanup.
 */
public class TetragonComponent implements StackComponent {

    /**
     * The events exported: those of the processes, and of the tracing policies, if any are added.
     */
    static final List<String> EVENT_SET = List.of(
        "PROCESS_EXEC", "PROCESS_EXIT", "PROCESS_KPROBE", "PROCESS_UPROBE", "PROCESS_TRACEPOINT", "PROCESS_LSM");

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(CrdCleanupComponent.class, MonitoringComponent.class);
    }

    @Override
    public void deploy(StackContext context) {
        CloudPlatform              platform  = context.platform();
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        // the chart creates the Services of the agent's and the operator's metrics, which fire the cloud's mutating
        // webhooks, if it has any, as Monitoring's do
        dependsOn.addAll(platform.getServiceDependables());
        dependsOn.add(context.require(CrdCleanupComponent.class).cleanup());
        Release.Builder
            .create(context.stack(), "tetragonRelease")
            .name("tetragon")
            .chart(vendoredChart(context.stack(), "tetragonChart", "tetragon-1.7.1.tgz"))
            // the namespace whose pods Promtail ships the logs of to Loki
            .namespace("default")
            .values(List.of(tetragonValues(platform.clusterName(), JupyterHubConfig.USER_POD_LABELS)))
            .timeout(600)
            .atomic(true)
            .forceUpdate(true)
            .dependsOn(dependsOn)
            .build();
    }

    /**
     * Values for the {@code cilium/tetragon} chart.  The allow list keeps only the events of the pods of the
     * {@code default} namespace with all the labels, ie the notebook pods, which needs the Kubernetes API for Tetragon
     * to know the labels of the pods; the chart's deny list still drops the events of the health checks.
     */
    static String tetragonValues(String clusterName, Map<String, String> podLabels) {
        String selector = podLabels.entrySet().stream()
                                   .map(label -> label.getKey() + "=" + label.getValue())
                                   .sorted()
                                   .collect(Collectors.joining(","));
        String eventSet = EVENT_SET.stream().map(event -> "\"" + event + "\"").collect(Collectors.joining(","));
        return String.format("""
            tetragon:
              # the cluster_name of every event, to tell the clusters apart in Loki
              clusterName: %1$s
              enableK8sAPI: true
              # one JSON object per line, the filters within an object all having to match
              exportAllowList: |-
                {"namespace":["default"],"labels":["%2$s"],"event_set":[%3$s]}
            export:
              # the export-stdout sidecar prints the export file, so that the events are in the logs of the pod
              mode: stdout
            """, clusterName, selector, eventSet);
    }
}
