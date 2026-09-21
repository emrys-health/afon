package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.*;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.cdk.NetworkConfig;
import health.emrys.afon.cdk.SparkConfig;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static health.emrys.afon.cdk.CdkHelper.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.config.AfonCloud.AWS;

public class AwsJupyterStack extends AwsPolarisStack {

    /**
     * Password the Jupyter VNC server is configured with. Kept in-cluster only — the
     * Guacamole web UI itself is intended to live behind oauth2-proxy.
     */
    protected final List<Service> loadBalancers;
    protected final SparkConfig   sparkOperatorConfig;
    protected final HelmProvider  helmProvider;
    private         String        DELETE_CRDS;
    protected       Resource      crdCleanup;

    public AwsJupyterStack(final Construct scope,
                           final AfonCloud id,
                           final AfonPropertyProvider properties) {
        super(scope, id, properties);
        List<ITerraformDependable> dependables = cloudProvider.getDependables();
        JupyterHubConfig jupyter = new AwsJupyterHubConfig(
            this,
            cloudProvider,
            properties,
            getKeyCloakConfig().getAliasRecord());
        NetworkConfig network = new NetworkConfig(this, properties);
        // Every Service the hub deploy creates (guacamole-web, the per-user VNC services,
        // jupyterhub-proxy-public) fires the AWS LB Controller's mutating webhook
        // (mservice.elbv2.k8s.aws), which intercepts all Service creations — not just
        // type: LoadBalancer. Depend on the controller's Helm release — installed atomically,
        // so Terraform waits for its pods to be Ready — otherwise the webhook Service can still
        // have zero endpoints and the apply fails with "no endpoints available for service
        // aws-load-balancer-webhook-service". Same reasoning as AwsSparkStack's LB Service.
        List<ITerraformDependable> jupyterDependables = new ArrayList<>(dependables);
        jupyterDependables.add(cloudProvider.getLbRelease());
        // `dependables` carries the kubeconfigUpdater and the Cilium release, both of which the
        // CiliumNetworkPolicy kubectl inside networkPolicies() needs in place first.
        jupyter.deployJupyterHub(jupyterDependables);
        network.networkPolicies(SPARK_CONNECTOR_NAME, dependables);
        loadBalancers = List.of(); // Jupyter is now exposed via ALB ingress, not a raw LoadBalancer Service

        // Provider is already configured (and required-provider versions pinned) by
        // AwsPolarisStack's constructor; configureHelm() is memoized, so this just
        // returns the existing instance for subclasses (eg AwsTrinoStack) that need it.
        helmProvider = cloudProvider.configureHelm();

        csiSecretsDriver(dependables);

        ServiceAccountMetadata accountMetadata = ServiceAccountMetadata
            .builder()
            .name(SPARK_SERVICE_ACCOUNT_NAME)
            .namespace("default")
            .build();
        ServiceAccount.Builder
            .create(this, "sparkApplicationSa")
            .metadata(accountMetadata)
            .build();

        cloudProvider.configureLoadBalancerDestroy(loadBalancers);
        sparkOperatorConfig = new SparkConfig(this, "sparkop", dependables);
    }

    // TODO possible improvements to cleanupCRDs strategies:
    //  - delete only the CRDs of specific releases (monitoring.coreos.com, cilium.io) rather than
    //    everything except spark;
    //  - poll until the CRDs are gone (bounded) instead of a fixed 5 minutes;
    //  - strip finalizers on the specific resources that hang (as CdkHelper.finalizeIngress does);
    //  - have Terraform own the CRDs (e.g. a separate CRDs chart) so they get normal ordering.
    protected void unregisterCRDs(List<? extends ITerraformDependable> dependsOn) {
        DELETE_CRDS = """
                      echo "cleanupCRDs: deleting every CRD except the spark ones - 5 sweeps, 60s apart, as a CRD can be slow to go or held by finalizers"
                      for i in {1..5}; do {
                          echo "cleanupCRDs: sweep $i of 5 - listing the remaining CRDs"

                          for CRD in $(kubectl get crds | awk '{print $1}' | grep -v spark) ; do {
                           echo "cleanupCRDs: sweep $i - removing finalizers from CRD $CRD, then deleting it"
                           timeout 30s kubectl patch crd $CRD --type=json -p='[{"op": "remove", "path": "/metadata/finalizers"}]' || echo "cleanupCRDs: sweep $i - could not remove finalizers from CRD $CRD (it may have no finalizers or already be gone)"
                           timeout 30s kubectl delete crd $CRD  --force || echo "cleanupCRDs: sweep $i - could not delete CRD $CRD (a later sweep retries it)"
                          } done &

                          echo "cleanupCRDs: sweep $i of 5 started in the background, waiting 60s before the next one"
                          sleep 60
                      } done
                      echo "cleanupCRDs: finished all 5 sweeps"
                      """;
        // Only destroy-time: this deletes every non-spark CRD in the cluster. Anything whose Helm
        // release manifest contains custom resources (kube-prometheus-stack has Prometheus,
        // Alertmanager, ServiceMonitor, PrometheusRule...) must therefore depend on this resource,
        // so that it is uninstalled first: `helm uninstall` cannot delete objects of a kind whose
        // CRD is already gone and fails with "failed to delete release". A resource with no
        // dependents (and no dependencies) is destroyed in Terraform's first wave, in parallel with
        // the Helm uninstalls, which is what used to break the Prometheus uninstall.
        crdCleanup = Resource.Builder
            .create(this, "cleanupCRDs")
            .dependsOn(dependsOn)
            .provisioners(uponDestroyRun(DELETE_CRDS))
            .build();
    }

    private void csiSecretsDriver(List<@NonNull ITerraformDependable> eksDependOn) {
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .dependsOn(eksDependOn)
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart(vendoredChart(this, "csiDriverChart", "secrets-store-csi-driver-1.5.4.tgz"))
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }

    public static void main(String[] args) {
        final App app = createApp();
        new AwsJupyterStack(app, AWS, new FilePropertyProvider());
        app.synth();
    }
}