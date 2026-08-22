package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.*;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
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
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static health.emrys.afon.cluster.Defaults.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.cluster.Defaults.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.ConfigUtils.cdktfDir;

public class AwsJupyterStack extends AwsPolarisStack {

    /**
     * Password the Jupyter VNC server is configured with. Kept in-cluster only — the
     * Guacamole web UI itself is intended to live behind oauth2-proxy.
     */
    protected final List<Service> loadBalancers;
    protected final SparkConfig   sparkOperatorConfig;
    protected final HelmProvider  helmProvider;
    private         String        DELETE_CRDS;

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
        NetworkConfig network = new NetworkConfig(this);
        // Every Service the hub deploy creates (guacamole-web, the per-user VNC services,
        // jupyterhub-proxy-public) fires the AWS LB Controller's mutating webhook
        // (mservice.elbv2.k8s.aws), which intercepts all Service creations — not just
        // type: LoadBalancer. Depend on the controller's Helm release — installed atomically,
        // so Terraform waits for its pods to be Ready — otherwise the webhook Service can still
        // have zero endpoints and the apply fails with "no endpoints available for service
        // aws-load-balancer-webhook-service". Same reasoning as AwsSparkStack's LB Service.
        List<ITerraformDependable> jupyterDependables = new ArrayList<>(dependables);
        jupyterDependables.add(cloudProvider.getLbRelease());
        network.networkPolicies(jupyter.deployJupyterHub(jupyterDependables), SPARK_CONNECTOR_NAME);
        loadBalancers = List.of(); // Jupyter is now exposed via ALB ingress, not a raw LoadBalancer Service

        HelmProviderKubernetes kubernetes = HelmProviderKubernetes
            .builder()
            .host(cloudProvider.getClusterEndpoint())
            .clusterCaCertificate(Fn.base64decode(cloudProvider.getBase64CertData()))
            .token(cloudProvider.getToken())
            .build();
        helmProvider = HelmProvider.Builder
            .create(this, "helm")
            .kubernetes(kubernetes)
            .build();
        this.addOverride(
            "terraform.required_providers.aws", Map.of(
                "source", "hashicorp/aws",
                "version", "6.27.0"   // 👈 Pin exact version
                                                      ));
        this.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.2.0"   // 👈 Pin exact version
                                                       ));
        this.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                // 3.2.1 fixes "Unexpected Identity Change" errors when a resource takes
                // extended time to become ready (as happened when an EKS token expired
                // mid-rollout of oauth2-proxy). Do not downgrade below 3.2.1.
                "version", "3.2.1"   // 👈 Pin exact version
                                                             ));

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

    protected void unregisterCRDs(List<? extends ITerraformDependable> dependsOn) {
        DELETE_CRDS = """
                      for i in {1..5}; do {
                          echo iteration $i
                      
                          for CRD in $(kubectl get crds | awk '{print $1}' | grep -v spark) ; do {
                           echo $CRD
                           timeout 30s kubectl patch crd $CRD --type=json -p='[{"op": "remove", "path": "/metadata/finalizers"}]' || echo could not patch $CRD 
                           timeout 30s kubectl delete crd $CRD  --force || echo could not delete $CRD
                          } done & 
                      
                          sleep 60
                      } done
                      """;
        Resource.Builder
            .create(this, "cleanupCRDs")
            .dependsOn(dependsOn)
            .provisioners(uponDestroyRun(DELETE_CRDS))
            .build();
        Resource.Builder
            .create(this, "cleanupCRDsNoDeps")
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
        final App app = new App(AppConfig.builder()
                .outdir(cdktfDir())
                .build());
        new AwsJupyterStack(app, AWS, new FilePropertyProvider());
        app.synth();
    }
}