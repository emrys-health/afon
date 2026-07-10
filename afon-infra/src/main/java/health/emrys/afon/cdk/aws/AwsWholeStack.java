package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.cdk.NetworkConfiguration;
import health.emrys.afon.cdk.SparkConfig;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.infra.Defaults.SPARK_SERVICE_ACCOUNT_NAME;

/***
 * Creates the markup to create the full stack in AWS.
 * Note: it does not create anything other than JSON files!
 * You need to run tofu on those files for the infra to be provisioned.
 * Don't forget to
 * <UL>
 *     <LI>
 *          set environment variables, something like <pre>KUBE_CONFIG_PATH=/home/henryp/.kube/config</pre>
 *     </LI>
 *      <LI>
 *          <pre>unalias aws</pre> if you're using the Dockerized version and put the actual binary in your
 *          <pre>PATH</pre>
 *      </LI>
 * </UL>
 * when you run the output
 */
public class AwsWholeStack extends AwsKubernetesPolaris {

    static final Logger logger                     = org.slf4j.LoggerFactory.getLogger(AwsWholeStack.class);
    public static final String NO_INSTALLATION_FINALIZERS = "kubectl patch installation default -p " +
                                                     "'{\"metadata\":{\"finalizers\":[]}}' --type=merge";

    /**
     * Password the Jupyter VNC server is configured with. Kept in-cluster only — the
     * Guacamole web UI itself is intended to live behind oauth2-proxy.
     */
    protected final List<Service> loadBalancers;
    protected final SparkConfig   sparkOperatorConfig;
    protected final HelmProvider  helmProvider;
    private         String        DELETE_CRDS;

    public AwsWholeStack(final Construct scope,
                         final SupportedCloudsEnum id,
                         final Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> dependables = cloudProvider.getDependables();
        JupyterHubConfig jupyter = new JupyterHubAwsConfig(
            this,
            cloudProvider,
            properties,
            getKeyCloakConfig().getAliasRecord());
        NetworkConfiguration network = new NetworkConfiguration(this);
        // Every Service the hub deploy creates (guacamole-web, the per-user VNC services,
        // jupyterhub-proxy-public) fires the AWS LB Controller's mutating webhook
        // (mservice.elbv2.k8s.aws), which intercepts all Service creations — not just
        // type: LoadBalancer. Depend on the controller's Helm release — installed atomically,
        // so Terraform waits for its pods to be Ready — otherwise the webhook Service can still
        // have zero endpoints and the apply fails with "no endpoints available for service
        // aws-load-balancer-webhook-service". Same reasoning as SparkClusterDeployment's LB Service.
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
                "version", "3.1.1"   // 👈 Pin exact version
                                                       ));
        this.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                "version", "3.0.1"   // 👈 Pin exact version
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

    private void csiSecretsDriver(List<@NotNull ITerraformDependable> eksDependOn) {
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .dependsOn(eksDependOn)
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .version("1.5.4")
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsWholeStack(app, ID, new AfonPropertiesLoader().getProperties());
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu " +
                    "-apply");
    }
}