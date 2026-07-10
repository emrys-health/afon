package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeploymentUtils {

    public static Deployment deploy(
        Construct                                   scope,
        List<@NotNull ITerraformDependable>         eksDependOn,
        Map<String, String>                         labels,
        List<DeploymentSpecTemplateSpecContainer>   containers,
        List<DeploymentSpecTemplateSpecVolume>      volumes,
        String                                      name) {
        return deploy(scope, eksDependOn, labels, containers, List.of(), volumes, name, null);
    }

    /**
     * As {@link #deploy(Construct, List, Map, List, List, String)}, additionally taking init containers
     * that must succeed before the main containers start, and an optional Terraform create timeout.
     *
     * @param createTimeout how long Terraform waits for the rollout, e.g. {@code "15m"}; the provider
     *                      default of 10m applies when {@code null}. Set this above the time any init
     *                      container may spend blocking, or Terraform gives up while the pod is still
     *                      legitimately waiting.
     */
    public static Deployment deploy(
        Construct                                     scope,
        List<@NotNull ITerraformDependable>           eksDependOn,
        Map<String, String>                           labels,
        List<DeploymentSpecTemplateSpecContainer>     containers,
        List<DeploymentSpecTemplateSpecInitContainer> initContainers,
        List<DeploymentSpecTemplateSpecVolume>        volumes,
        String                                        name,
        String                                        createTimeout) {
        // Mirror `app` onto the canonical Kubernetes label so Hubble UI shows
        // a real workload name rather than "Unknown App". Only the pod template
        // gets the extra label — the selector and Deployment metadata keep the
        // original label set so this is a zero-recreate change for existing
        // resources (selectors are immutable once a Deployment exists).
        Map<String, String> podLabels = withAppKubernetesIoName(labels);
        DeploymentSpecTemplateMetadata metadata = DeploymentSpecTemplateMetadata.builder().labels(podLabels).build();
        DeploymentSpecTemplateSpec.Builder podSpec = DeploymentSpecTemplateSpec.builder()
                                                                               .container(containers)
                                                                               .volume(volumes);
        // Left unset when empty so callers that pass none keep emitting no init_container block at all.
        if (!initContainers.isEmpty()) {
            podSpec.initContainer(initContainers);
        }
        DeploymentSpecTemplate specTemplate = DeploymentSpecTemplate
            .builder()
            .metadata(metadata)
            .spec(podSpec.build())
            .build();
        Deployment.Builder builder = Deployment.Builder.create(scope, name + "Deployment")
                                 .dependsOn(eksDependOn)
                                 .metadata(DeploymentMetadata
                                               .builder()
                                               .name(name + "-deployment")
                                               .labels(labels)
                                               .build())
                                 .spec(DeploymentSpec
                                           .builder()
                                           .selector(DeploymentSpecSelector
                                                         .builder()
                                                         .matchLabels(labels)
                                                         .build())
                                           .replicas("1")
                                           .template(specTemplate)
                                           .build());
        if (createTimeout != null) {
            builder.timeouts(DeploymentTimeouts.builder().create(createTimeout).build());
        }
        return builder.build();
    }

    /**
     * Returns a copy of {@code labels} with {@code app.kubernetes.io/name} added
     * (taken from the existing {@code app} value, or {@code k8s-app}, or the
     * caller-supplied label set's only entry as a last resort). If
     * {@code app.kubernetes.io/name} is already set the input is returned
     * unchanged.
     */
    static Map<String, String> withAppKubernetesIoName(Map<String, String> labels) {
        if (labels.containsKey("app.kubernetes.io/name")) {
            return labels;
        }
        String canonical = labels.getOrDefault("app", labels.get("k8s-app"));
        if (canonical == null) {
            return labels;
        }
        Map<String, String> enriched = new LinkedHashMap<>(labels);
        enriched.put("app.kubernetes.io/name", canonical);
        return Map.copyOf(enriched);
    }
}
