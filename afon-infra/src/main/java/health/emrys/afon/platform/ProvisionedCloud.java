package health.emrys.afon.platform;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import health.emrys.afon.cdk.JupyterConfig;

import java.util.List;

/**
 * The result of {@link CloudProviderPlugin#provision}: a cloud whose Kubernetes
 * cluster, storage and Polaris are declared, ready for query engines and a
 * workbench to be layered on top by {@link QueryEngineDeployer} /
 * {@link WorkbenchDeployer}.
 * <p>
 * In Phase 2 the implementations are the existing per-cloud stacks
 * ({@code AwsKubernetesPolaris}, {@code AzureKubernetesPolaris},
 * {@code GcpKubernetesPolaris}); the interface deliberately mirrors what those
 * stacks already offer so implementing it changes no synthesised output.
 */
public interface ProvisionedCloud {

    /** The cloud's id — today the {@code SupportedCloudsEnum} value, e.g. "aws". */
    String cloudId();

    /** The stack all engine/workbench constructs for this cloud are scoped to. */
    TerraformStack stack();

    /** Resources that must exist before anything touches the cluster's API server. */
    List<ITerraformDependable> dependables();

    /**
     * Resources proving the cluster's data plane can actually schedule pods.
     * On AWS this equals {@link #dependables()}; on Azure it is the nginx
     * release, which having scheduled pods proves the Cilium CNI is serving
     * (see the comment in {@code AzureWholeStack}).
     */
    List<ITerraformDependable> workloadReadiness();

    KubernetesProvider k8sProvider();

    HelmProvider helmProvider();

    String polarisDomainName();

    /**
     * A Jupyter deployment wired for this cloud's ingress/DNS/OAuth specifics.
     * Migration shim: workbench selection generalises in Phase 3.
     */
    JupyterConfig newJupyterConfig();

    /**
     * Whether Jupyter pods get the restrictive egress {@code NetworkPolicy}
     * ({@code NetworkConfiguration#networkPolicies}). Preserves current
     * behaviour: applied on AWS, not (yet) on Azure.
     */
    boolean lockDownJupyterEgress();

    /**
     * Stack-level production hardening the legacy {@code *WholeStack} classes
     * applied on top of the bare cloud (on AWS: pinned provider versions and
     * the secrets-store CSI driver). The config-driven planner calls this after
     * {@link CloudProviderPlugin#provision}; clouds with no extra hardening
     * inherit the no-op.
     */
    default void hardenStack() {
    }

    /**
     * Cloud-specific finishing touches once Spark is deployed, given resources
     * proving Spark is schedulable (cluster dependables + the operator-ready
     * signal). On AWS ({@code SparkClusterDeployment} legacy behaviour): the
     * spark-master LoadBalancer Service and the CRD-cleanup destroy
     * provisioners. Elsewhere: nothing.
     */
    default void exposeSparkMaster(List<ITerraformDependable> sparkReady) {
    }

    /**
     * Publishes a workbench's auth proxy on {@code domain} using this cloud's
     * ingress + DNS machinery (on AWS: ALB ingress + Route53 alias; on Azure:
     * nginx ingress + cert-manager + A record). Called by
     * {@link WorkbenchDeployer}s that front a web UI with
     * {@code AuthProxyConfig} (VS Code, RStudio); Jupyter keeps its legacy
     * per-cloud {@code JupyterConfig} wiring.
     *
     * @param ingressName Kubernetes name of the Ingress (also seeds construct ids)
     * @param domain      public domain the workbench is served on
     * @param serviceName the in-cluster Service to route to (the auth proxy)
     * @param servicePort the Service's port
     */
    default void exposeWorkbench(String ingressName, String domain, String serviceName, int servicePort) {
        throw new UnsupportedOperationException(
            cloudId() + " cannot expose a workbench ingress yet");
    }
}
