package health.emrys.afon.cdk;

import com.hashicorp.cdktf.AssetType;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformAsset;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class CdkUtils {

    public static @NonNull List<LocalExecProvisioner> uponDestroyRun(String... commands) {
        return Arrays.stream(commands).map(command ->
                                                   LocalExecProvisioner
                                                           .builder()
                                                           .type("local-exec")
                                                           .when("destroy")
                                                           .command(addingOrClauseIfNecessary(command))
                                                           .build()
                                          ).toList();
    }

    public static @NonNull List<LocalExecProvisioner> uponCreateRun(String... commands) {
        return Arrays.stream(commands).map(command ->
                                                   LocalExecProvisioner
                                                           .builder()
                                                           .type("local-exec")
                                                           .when("create")
                                                           .command(command)
                                                           .build()
                                          ).toList();
    }

    private static final String addingOrClauseIfNecessary(String command) {
        if (!(command.endsWith("\n") || command.endsWith("\r"))) {
            return command + " || echo FAILED";
        }
        return command;
    }

    public static Deployment deploy(
        Construct scope,
        List<@NonNull ITerraformDependable>         eksDependOn,
        Map<String, String> labels,
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
        List<@NonNull ITerraformDependable>           eksDependOn,
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
        DeploymentSpec.Builder deploymentSpec = DeploymentSpec
            .builder()
            .selector(DeploymentSpecSelector
                          .builder()
                          .matchLabels(labels)
                          .build())
            .replicas("1")
            .template(specTemplate);
        // Kubernetes fails a rollout once its own progressDeadlineSeconds (default 600s / 10m)
        // elapses, surfacing a "Deployment exceeded its progress deadline" condition that the
        // provider reports as an error long before the Terraform create timeout below is reached.
        // Keep the two in step so a caller raising createTimeout to cover a slow init container
        // actually gets the extra wait rather than tripping the 10m Kubernetes default.
        if (createTimeout != null) {
            deploymentSpec.progressDeadlineSeconds(durationToSeconds(createTimeout));
        }
        Deployment.Builder builder = Deployment.Builder.create(scope, name + "Deployment")
                                 .dependsOn(eksDependOn)
                                 // Don't block the apply waiting for pods to become Ready. These are
                                 // app workloads reached at runtime via ALB/ingress — nothing downstream
                                 // in Terraform depends on their readiness, so waiting only burns wall-clock
                                 // under the 15-minute EKS auth token and risks the token expiring
                                 // mid-rollout (which is how oauth2-proxy failed). Trade-off: a genuinely
                                 // broken rollout won't fail the apply — surface it via monitoring/kubectl.
                                 .waitForRollout(false)
                                 .metadata(DeploymentMetadata
                                               .builder()
                                               .name(name + "-deployment")
                                               .labels(labels)
                                               .build())
                                 .spec(deploymentSpec.build());
        if (createTimeout != null) {
            builder.timeouts(DeploymentTimeouts.builder().create(createTimeout).build());
        }
        return builder.build();
    }

    /**
     * Converts a Terraform-style Go duration (e.g. {@code "15m"}, {@code "600s"}, {@code "1h"}) to
     * whole seconds for {@code progressDeadlineSeconds}. Supports {@code h}/{@code m}/{@code s} units,
     * defaulting to seconds when no unit is given.
     */
    static Number durationToSeconds(String duration) {
        String trimmed = duration.trim();
        char unit = trimmed.charAt(trimmed.length() - 1);
        long multiplier = switch (unit) {
            case 'h' -> 3600;
            case 'm' -> 60;
            case 's' -> 1;
            default  -> 0;
        };
        if (multiplier == 0) {
            return Long.parseLong(trimmed);
        }
        return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * multiplier;
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

    /**
     * Path to a Helm chart vendored into {@code src/main/resources/charts}, for use as
     * {@code Release.chart()} with no {@code repository()}.
     * <p>
     * Charts pulled at apply time come from GitHub Pages, and a chart repository's
     * {@code index.yaml} can run to megabytes (Cilium's is ~1.7MB). A single reset mid-read
     * fails the whole apply with the provider's misleading "not a valid chart repository"
     * error, so any flaky link between here and GitHub breaks the deployment. Vendoring the
     * tarball removes that fetch — and pins the exact bytes we tested against, which a
     * version constraint alone does not guarantee.
     * <p>
     * The resource is staged through {@link TerraformAsset} so it is copied into the stack's
     * output directory at synth time and referenced by a path relative to it. That keeps the
     * generated {@code cdk.tf.json} free of absolute paths from the synthesising machine, and
     * means the chart resolves no matter which directory synth or apply was invoked from.
     *
     * @param scope     the stack the asset belongs to.
     * @param id        unique construct id for the asset.
     * @param chartFile file name under {@code resources/charts}, e.g. {@code cilium-1.15.0.tgz}.
     * @return the chart path to hand to {@code Release.chart()}.
     */
    public static @NonNull String vendoredChart(@NonNull Construct scope,
                                                @NonNull String id,
                                                @NonNull String chartFile) {
        String resource = "/charts/" + chartFile;
        try (InputStream chart = CdkUtils.class.getResourceAsStream(resource)) {
            if (chart == null) {
                throw new IllegalArgumentException("No vendored chart on the classpath at " + resource);
            }
            // TerraformAsset needs a file on disk, but the chart lives in the jar. Stage it in a
            // temp directory that is deleted on JVM exit; synth copies it onwards before then.
            Path staging = Files.createTempDirectory("afon-chart-");
            staging.toFile().deleteOnExit();
            Path staged = staging.resolve(chartFile);
            Files.copy(chart, staged, StandardCopyOption.REPLACE_EXISTING);
            staged.toFile().deleteOnExit();
            return TerraformAsset.Builder
                .create(scope, id)
                .path(staged.toString())
                .type(AssetType.FILE)
                .build()
                .getPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage vendored chart " + chartFile, e);
        }
    }

    public static Map<String, String> addMaps(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> results = new HashMap<>(base);
        results.putAll(overrides);
        return results;
    }
}
