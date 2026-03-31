package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AwsWholeStack.NO_INSTALLATION_FINALIZERS;
import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;

public class AwsKubernetesPolaris extends TerraformStack implements EmrysCloud {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsKubernetesPolaris.class);

    public static final SupportedCloudsEnum ID               = AWS;
    public static final String              POLARIS_INTERNAL = "polaris-internal";

    protected final PolarisConfig     polarisConfig;
    protected final AwsConstruct      cloudProvider;
    protected final Service       polarisLB;
    protected final Release       calico;
    protected final List<Service> loadBalancers;
    protected final List<ITerraformDependable> afterCilium;
    protected       Resource      disableKubeProxy;

    public AwsKubernetesPolaris(@NotNull Construct scope, SupportedCloudsEnum id) {
        super(scope, id.toString());
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        // HelmProvider.Builder.create(this, "helm")
        //                     .build();

        cloudProvider = new AwsConstruct(this, id.toString());

        // Install Cilium and disable kube-proxy first. Every workload that schedules pods
        // must depend on disableKubeProxy so its pods are never created before Cilium's BPF
        // programs are initialised on every node (which caused CrashLoopBackOff on first apply).
        List<ITerraformDependable> dependables = new ArrayList<>();
        calico      = calico(cloudProvider.getDependables());
        dependables.add(disableKubeProxy);
        dependables.add(calico);
        afterCilium = dependables;

        csiSecretsDriver(dependables);

        loadBalancers = List.of(); // Jupyter is now exposed via ALB ingress, not a raw LoadBalancer Service
        cloudProvider.installLbcAndIngress(dependables);


        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return null; // no longer using an external service
            }
        };
        polarisLB     = polarisConfig.deployPolaris(afterCilium);
        Service.Builder.create(this, POLARIS_INTERNAL)
                       .metadata(ServiceMetadata.builder()
                                                .name(POLARIS_INTERNAL)
                                                .namespace("default")
                                                .build())
                       .dependsOn(afterCilium)
                       .spec(ServiceSpec.builder()
                                        .selector(polarisConfig.polarisLabels) // Selects the Pods created by secretAppDeployment
                                        .type("NodePort")
                                        .internalTrafficPolicy("Cluster")
//                                        .clusterIp("None")
                                        .clusterIps(List.of())
                                        .port(List.of(polarisConfig.POLARIS_PORTS))
                                        .build())
                       .build();
    }


    private Release calico(List<@NotNull ITerraformDependable> eksDependOn) {
        /* This is still timing out after 10 minutes with:
│ Error: Error uninstalling release
│
│ Unable to uninstall Helm release tigera-operator: uninstallation completed with 1 error(s): context deadline exceeded
╵
        running tofu destroy a second time works.
         */
        Release ciliumRelease = Release.Builder.create(this, "cilium")
                                               .name("cilium")
                                               .dependsOn(eksDependOn)
                                               .chart("cilium")
                                               .version("1.19.1")
                                               .namespace("kube-system")
                                               .cleanupOnFail(true)
                                               .disableCrdHooks(true)
                                               .disableWebhooks(true)
                                               .forceUpdate(true)
                                               .recreatePods(false)
                                               .set(List.of(
                                                   // ENI mode: Cilium manages AWS ENIs directly (no overlay tunnel)
                                                   ReleaseSet.builder().name("eni.enabled").value("true").build(),
                                                   ReleaseSet.builder().name("ipam.mode").value("eni").build(),
                                                   ReleaseSet.builder().name("routingMode").value("native").build(),
                                                   ReleaseSet.builder().name("ipv4NativeRoutingCIDR").value(cloudProvider.vpc.getCidrBlock()).build(),
                                                   // BPF masquerade: required with kubeProxyReplacement=true as BPF bypasses iptables
                                                   ReleaseSet.builder().name("bpf.masquerade").value("true").build(),
                                                   ReleaseSet.builder().name("enableIPv4Masquerade").value("true").build(),
                                                   ReleaseSet.builder().name("kubeProxyReplacement").value("true").build(),
                                                   ReleaseSet.builder().name("k8sServiceHost").value(com.hashicorp.cdktf.Fn.trimprefix(cloudProvider.getClusterEndpoint(), "https://")).build(),
                                                   ReleaseSet.builder().name("k8sServicePort").value("443").build(),
                                                   // nodeinit patches aws-node to a no-op DaemonSet on first boot
                                                   ReleaseSet.builder().name("nodeinit.enabled").value("true").build(),
                                                   ReleaseSet.builder().name("nodeinit.reconfigureKubelet").value("true").build(),
                                                   ReleaseSet.builder().name("nodeinit.removeCbrBridge").value("false").build(),
                                                   ReleaseSet.builder().name("crds.install").value("true").build(),
                                                   ReleaseSet.builder().name("crds.cleanup").value("false").build()
                                                           ))
                                               .provisioners(uponDestroyRun(NO_INSTALLATION_FINALIZERS,
                                                                            "kubectl delete ns tigera-operator --force --grace-period=0",
                                                   // Remove Cilium CNI config and binaries so nodes can rejoin cleanly
                                                                            "kubectl -n kube-system delete daemonset cilium --force --grace-period=0 || true",
                                                                            "kubectl -n kube-system delete daemonset cilium-node-init --force --grace-period=0 || true"
                                                                           ))
                                               .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                               .repository("https://helm.cilium.io/")
                                               .createNamespace(true)
                                               .atomic(false)
                                               .timeout(1200)
                                               .build();

        // kube-proxy must not run alongside Cilium's kube-proxy replacement — BPF and iptables
        // service routing conflict, breaking ClusterIP services (including CoreDNS).
        //
        // Three-step create sequence:
        // 1. Patch kube-proxy off.
        // 2. Wait for cilium-node-init to finish on every node: it reconfigures kubelet, and any
        //    pod whose network namespace is created before kubelet restarts gets broken networking
        //    that persists across CrashLoopBackOff restarts.
        // 3. Run a disposable busybox pod and verify it can actually reach the API server via
        //    ClusterIP — the only reliable proof that Cilium's BPF is ready for new pod traffic.
        //    Terraform will not proceed to schedule any workloads until this probe exits 0.
        disableKubeProxy = Resource.Builder.create(this, "disableKubeProxy")
                                           .dependsOn(List.of(ciliumRelease))
                                           .provisioners(Stream.concat(
                                               uponCreateRun(
                                                   "kubectl -n kube-system patch daemonset kube-proxy -p '{\"spec\":{\"template\":{\"spec\":{\"nodeSelector\":{\"non-existing\":\"true\"}}}}}'",
                                                   "kubectl -n kube-system rollout status daemonset/cilium-node-init --timeout=180s || true",
                                                   "kubectl -n kube-system wait --for=condition=ready pod -l k8s-app=cilium --timeout=300s",
                                                   // Run a connectivity probe on EVERY node, retrying up to 3 times with a 60s
                                                   // pause between attempts. Cilium may still be compiling BPF templates or
                                                   // settling ENI interface mappings when it first reports Ready; retries absorb
                                                   // that window without failing the whole deployment.
                                                   // Loop 1 (per attempt): launch all per-node probes in parallel.
                                                   // Loop 2 (per attempt): collect all results before deciding pass/fail so every
                                                   // probe pod is cleaned up regardless of outcome.
                                                   "FAIL=0; " +
                                                   "for ATTEMPT in 1 2 3; do " +
                                                   "FAIL=0; " +
                                                   "for NODE in $(kubectl get nodes -o jsonpath='{.items[*].metadata.name}'); do " +
                                                   "NAME=cilium-probe-$(echo \"$NODE\" | tr '.' '-'); " +
                                                   "kubectl delete pod \"$NAME\" -n kube-system --ignore-not-found --wait=false 2>/dev/null || true; " +
                                                   "kubectl run \"$NAME\" -n kube-system --image=busybox:1.36 --restart=Never " +
                                                   "--overrides=\"{\\\"spec\\\":{\\\"nodeSelector\\\":{\\\"kubernetes.io/hostname\\\":\\\"$NODE\\\"}," +
                                                   "\\\"tolerations\\\":[{\\\"operator\\\":\\\"Exists\\\"}]}}\" " +
                                                   // Use verbose wget (no -q) and echo K8S_HOST so pod logs reveal whether the
                                                   // env var is set and what wget error occurs on failing nodes.
                                                   // 10 retries x ~15s each = ~150s max, well within kubectl wait timeout.
                                                   // nc tests TCP connectivity to the API server ClusterIP (172.20.0.1:443).
                                                   // A successful TCP handshake proves Cilium's BPF socket LB is translating the
                                                   // ClusterIP to a real endpoint.  We deliberately avoid HTTPS: busybox 1.36's
                                                   // TLS implementation is rejected by the EKS API server (alert code 47 =
                                                   // illegal_parameter), causing false negatives even when networking is healthy.
                                                   "-- sh -c 'echo K8S_HOST=$KUBERNETES_SERVICE_HOST; " +
                                                   "for i in $(seq 1 30); do " +
                                                   "nc -w5 $KUBERNETES_SERVICE_HOST 443 </dev/null 2>&1 && exit 0; " +
                                                   "echo attempt $i failed; sleep 10; done; exit 1'; " +
                                                   "done; " +
                                                   "for NODE in $(kubectl get nodes -o jsonpath='{.items[*].metadata.name}'); do " +
                                                   "NAME=cilium-probe-$(echo \"$NODE\" | tr '.' '-'); " +
                                                   "kubectl wait -n kube-system \"pod/$NAME\" --for=condition=Ready=false --timeout=600s || true; " +
                                                   "PHASE=$(kubectl get -n kube-system pod \"$NAME\" -o jsonpath='{.status.phase}' 2>/dev/null); " +
                                                   "echo \"=== Node $NODE probe phase: $PHASE ===\"; " +
                                                   "kubectl logs -n kube-system \"$NAME\" 2>/dev/null | tail -8 || true; " +
                                                   "kubectl delete -n kube-system pod \"$NAME\" --ignore-not-found || true; " +
                                                   "[ \"$PHASE\" = \"Succeeded\" ] || FAIL=1; " +
                                                   "done; " +
                                                   "[ \"$FAIL\" -eq 0 ] && break; " +
                                                   "[ \"$ATTEMPT\" -lt 3 ] && echo \"Cilium probe attempt $ATTEMPT failed, sleeping 60s...\" && sleep 60; " +
                                                   "done; " +
                                                   "[ \"$FAIL\" -eq 0 ] || exit 1"
                                               ).stream(),
                                               uponDestroyRun("kubectl -n kube-system patch daemonset kube-proxy -p '{\"spec\":{\"template\":{\"spec\":{\"nodeSelector\":{\"kubernetes.io/os\":\"linux\"}}}}}'").stream()
                                                                      ).toList())
                                           .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                           .build();
        return ciliumRelease;
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
                       .atomic(false) // If installation fails, completely uninstall the release
                       .build();
    }


    public static void main(String[] args) {
        final App app = new App();
        new AwsKubernetesPolaris(app, ID);
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu -apply");
    }

    @Override
    public SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return AWS_POLARIS_DOMAIN_NAME;
    }
}
