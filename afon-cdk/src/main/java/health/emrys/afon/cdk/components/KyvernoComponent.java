package health.emrys.afon.cdk.components;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.PublicKeys;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.toCanonicalJson;
import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Kyverno, the policy engine whose admission controller checks that the images of the pods are signed with the cosign key of the
 * cloud, see {@code docker-image --sign}.
 * <p>
 * Which namespaces are checked, and how, is decided by the label {@value #NAMESPACE_LABEL}, so that a namespace goes from audited to
 * enforced by changing its label (see {@link Mode}), and a namespace without it is never checked, as those of Kyverno and the system:
 * <ul>
 * <li>{@code audit}: the {@code ImageValidatingPolicy} {@value #AUDIT_POLICY} only records the images that are not signed, in the policy
 * reports, and admits the pod.  Its failure policy is {@code Ignore}, so pods are admitted if Kyverno is down.  It is the mode of the
 * {@value #WORKLOAD_NAMESPACE} namespace, where the workloads run, unless it is enforced;
 * <li>{@code enforce}: {@value #ENFORCE_POLICY} rejects the pod, and its failure policy is {@code Fail}, so that pods are also rejected if
 * Kyverno is down, as an image cannot then be checked.  The namespaces are those in {@code <CLOUD>_IMAGE_POLICY_ENFORCED_NAMESPACES},
 * none by default.
 * </ul>
 * Both check the {@code containers} and the {@code initContainers}, against the public key alone, {@code <CLOUD>_COSIGN_PUBLIC_KEY}, and
 * ignore the transparency log, which the signatures do not use, so that the admission controller only needs to reach the registry.
 * <p>
 * Without that key the engine is installed and no policy is applied.
 * <p>
 * The policies are applied with kubectl rather than a {@code kubernetes_manifest} resource, as that contacts the API server during plan,
 * before the CRDs of Kyverno are registered.
 */
public class KyvernoComponent implements StackComponent {

    private static final Logger logger = getLogger(KyvernoComponent.class);

    /**
     * How the images of the pods of a namespace are checked, by the value of its label {@value #NAMESPACE_LABEL}.
     *
     * @param label   the value of the label
     * @param policy  the name of the policy
     * @param action  the {@code validationActions} of the policy
     * @param failure the {@code failurePolicy} of the policy
     */
    public enum Mode {
        AUDIT("audit", AUDIT_POLICY, "Audit", "Ignore"),
        ENFORCE("enforce", ENFORCE_POLICY, "Deny", "Fail");

        public final String label;
        public final String policy;
        final String action;
        final String failure;

        Mode(String label, String policy, String action, String failure) {
            this.label = label;
            this.policy = policy;
            this.action = action;
            this.failure = failure;
        }
    }

    public static final String AUDIT_POLICY = "emrys-verify-images-audit";
    public static final String ENFORCE_POLICY = "emrys-verify-images-enforce";
    public static final String NAMESPACE_LABEL = "afon.emrys.health/verify-images";
    /**
     * The namespace of the workloads, which is audited unless enforced.
     */
    static final String WORKLOAD_NAMESPACE = "default";
    static final String ATTESTOR = "emrys";

    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        // the chart creates Services, which fire the cloud's mutating webhooks, if it has any
        dependsOn.addAll(platform.getServiceDependables());
        Release kyverno = Release.Builder
            .create(context.stack(), "kyvernoRelease")
            .name("kyverno")
            .chart(vendoredChart(context.stack(), "kyvernoChart", "kyverno-3.9.2.tgz"))
            .namespace("kyverno")
            .createNamespace(true)
            .timeout(600)
            .atomic(true)
            .dependsOn(dependsOn)
            .build();

        AfonCloud cloud = context.stackId().cloud();
        String publicKey = PublicKeys.fromProperty(context.properties().getCloudValue(cloud, AfonCloudProperty.COSIGN_PUBLIC_KEY, ""));
        if (publicKey.isEmpty()) {
            logger.warn("Kyverno is deployed with no image policy: set {}", AfonCloudProperty.COSIGN_PUBLIC_KEY.forCloud(cloud).orElseThrow());
            return;
        }
        List<String> enforced = namespaces(context.properties().getCloudValue(cloud, AfonCloudProperty.IMAGE_POLICY_ENFORCED_NAMESPACES, ""));
        List<String> audited = enforced.contains(WORKLOAD_NAMESPACE) ? List.of() : List.of(WORKLOAD_NAMESPACE);
        String auditPolicy = toCanonicalJson(imagePolicy(publicKey, Mode.AUDIT));
        String enforcePolicy = toCanonicalJson(imagePolicy(publicKey, Mode.ENFORCE));
        List<ITerraformDependable> policyDependsOn = new ArrayList<>(dependsOn);
        policyDependsOn.add(kyverno);
        Resource.Builder.create(context.stack(), "kyvernoImagePolicy")
                        .dependsOn(policyDependsOn)
                        // keyed on the policies and the namespaces, so that a change of key, policy or rollout applies them again
                        .triggers(Map.of("audit", auditPolicy, "enforce", enforcePolicy,
                                         "auditedNamespaces", String.join(",", audited), "enforcedNamespaces", String.join(",", enforced)))
                        .provisioners(provisioners(auditPolicy, enforcePolicy, audited, enforced))
                        .build();
    }

    /**
     * @param csv comma separated namespaces
     * @return the namespaces, trimmed and without duplicates, in order
     */
    static List<String> namespaces(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
    }

    private static List<com.hashicorp.cdktf.LocalExecProvisioner> provisioners(String auditPolicy, String enforcePolicy,
                                                                               List<String> audited, List<String> enforced) {
        List<String> create = new ArrayList<>();
        audited.forEach(namespace -> create.add(label(namespace, Mode.AUDIT)));
        enforced.forEach(namespace -> create.add(label(namespace, Mode.ENFORCE)));
        create.add(writeCommand(auditPolicy) + " | kubectl apply -f -");
        create.add(writeCommand(enforcePolicy) + " | kubectl apply -f -");
        List<com.hashicorp.cdktf.LocalExecProvisioner> provisioners = new ArrayList<>(uponCreateRun(create.toArray(String[]::new)));
        provisioners.addAll(uponDestroyRun(
            "kubectl delete imagevalidatingpolicies.policies.kyverno.io " + AUDIT_POLICY + " " + ENFORCE_POLICY + " --ignore-not-found"));
        return provisioners;
    }

    /**
     * @return the command that writes the JSON as it is, which {@code echo} would not, as that of {@code sh} turns the {@code \n} that the
     * JSON has for the new lines of the key into new lines
     */
    static String writeCommand(String json) {
        return "printf '%s\\n' " + shellQuote(json);
    }

    /**
     * @return the text as one word of a shell command, with any single quote in it closed, escaped and opened again
     */
    static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private static String label(String namespace, Mode mode) {
        return "kubectl label namespace " + namespace + " " + NAMESPACE_LABEL + "=" + mode.label + " --overwrite";
    }

    /**
     * @param publicKey the PEM public key the images are signed with
     * @param mode      whether to audit the images or enforce that they are signed
     * @return the {@code ImageValidatingPolicy} that checks that the images of the pods, init containers included, of the namespaces labelled
     * with the mode are signed with it
     */
    public static Map<String, Object> imagePolicy(String publicKey, Mode mode) {
        return Map.of(
            "apiVersion", "policies.kyverno.io/v1",
            "kind", "ImageValidatingPolicy",
            "metadata", Map.of("name", mode.policy),
            "spec", Map.of(
                "validationActions", List.of(mode.action),
                "failurePolicy", mode.failure,
                "matchConstraints", Map.of(
                    "namespaceSelector", Map.of("matchLabels", Map.of(NAMESPACE_LABEL, mode.label)),
                    "resourceRules", List.of(Map.of(
                        "apiGroups", List.of(""),
                        "apiVersions", List.of("v1"),
                        "operations", List.of("CREATE", "UPDATE"),
                        "resources", List.of("pods")))),
                // every image: one that is not signed by us is a violation
                "matchImageReferences", List.of(Map.of("glob", "**")),
                "attestors", List.of(Map.of(
                    "name", ATTESTOR,
                    "cosign", Map.of(
                        "key", Map.of("data", publicKey),
                        "ctlog", Map.of("insecureIgnoreTlog", true, "insecureIgnoreSCT", true)))),
                "validations", List.of(Map.of(
                    "expression", "(images.containers + images.initContainers).map(image, verifyImageSignatures(image, [attestors."
                        + ATTESTOR + "])).all(e, e > 0)",
                    "message", "The image is not signed with the key of the cloud of the cluster"))));
    }
}
