package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.CrdCleanup;
import health.emrys.afon.cdk.component.StackComponent;
import health.emrys.afon.cdk.component.StackContext;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;

/**
 * Only on destroy, deletes the CRDs registered by the monitoring stack and Cilium (see {@link CrdCleanup#command}).
 * Anything whose Helm release manifest contains custom resources (kube-prometheus-stack has Prometheus,
 * Alertmanager, ServiceMonitor, PrometheusRule...) must therefore depend on {@link #cleanup()}, so that it is
 * uninstalled first: {@code helm uninstall} cannot delete objects of a kind whose CRD is already gone and fails with
 * "failed to delete release".  A resource with no dependents (and no dependencies) is destroyed in Terraform's first
 * wave, in parallel with the Helm uninstalls, which is what used to break the Prometheus uninstall.
 * <p>
 * It depends on the cluster-level dependencies only, not on the Spark load balancer, as anything more would also
 * serialise the install of what depends on it behind that load balancer.
 */
public class AwsCrdCleanupComponent implements StackComponent {

    private Resource cleanup;

    @Override
    public void deploy(StackContext context) {
        cleanup = Resource.Builder
            .create(context.stack(), "cleanupCRDs")
            .dependsOn(context.platform().getDependables())
            .provisioners(uponDestroyRun(CrdCleanup.provisionerCommand()))
            .build();
    }

    public Resource cleanup() {
        return cleanup;
    }
}
