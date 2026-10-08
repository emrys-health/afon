package health.emrys.afon.cdk;

import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.cdk.CdkUtils.escapeTerraformTemplate;

/**
 * The destroy-time cleanup of the CRDs left behind by the monitoring stack and Cilium, run by the
 * {@code cleanupCRDs} resource of {@link health.emrys.afon.cdk.components.CrdCleanupComponent} on every
 * {@code tofu destroy}.
 */
public final class CrdCleanup {

    private CrdCleanup() {}

    /**
     * API groups whose CRDs are removed by {@link #command()}: the ones registered by
     * the monitoring stack (kube-prometheus-stack and the Loki chart's grafana-agent-operator) and
     * by Cilium. Helm never removes CRDs shipped in a chart's {@code crds/} directory, and the
     * {@code cilium.io} ones are registered by the Cilium operator at runtime, so no release owns
     * them and they would otherwise be left behind. Everything else (the Spark operator's CRDs, the
     * AWS-managed {@code *.k8s.aws} ones, cert-manager, ...) is deliberately left alone.
     */
    static final List<String> GROUPS = List.of("monitoring.coreos.com", "monitoring.grafana.com", "cilium.io");

    /**
     * Destroy-time (a {@code local-exec}) removal of the CRDs in {@link #GROUPS}, run once the
     * releases that own custom resources of those kinds have been uninstalled. It never fails the
     * destroy: whatever it cannot remove within the time budget is reported on stderr instead.
     *
     * <p>Rather than sleeping a fixed time it repeatedly asks for the CRDs to be deleted and polls
     * until they are gone, escalating only if they hang: a CRD stays in {@code Terminating} while
     * custom resources of that kind still carry finalizers of a controller that is no longer running,
     * so the finalizers of those specific resources are removed (as
     * {@link CdkHelper#finalizeIngress(String)} does for Ingresses); only if the
     * CRD itself is still there after a grace period are its own finalizers removed.
     *
     * <p>Tunable through the environment, which the unit test relies on:
     * {@code CRD_CLEANUP_MAX_WAIT} (300s), {@code CRD_CLEANUP_GRACE} (60s) and
     * {@code CRD_CLEANUP_INTERVAL} (5s).
     */
    public static @NonNull String command() {
        String pattern = "[.](" + String.join("|", GROUPS).replace(".", "[.]") + ")$";
        return """
            CRD_PATTERN='@CRD_PATTERN@'
            MAX_WAIT=${CRD_CLEANUP_MAX_WAIT:-300}
            FINALIZER_GRACE=${CRD_CLEANUP_GRACE:-60}
            INTERVAL=${CRD_CLEANUP_INTERVAL:-5}
            RT=--request-timeout=30s
            echo "cleanupCRDs: removing the CRDs of the groups @GROUPS@ (giving up after ${MAX_WAIT}s)"

            # $1 is a fully qualified resource, eg prometheuses.monitoring.coreos.com. Only the resources
            # that actually carry finalizers are patched; those are what keeps their CRD in Terminating.
            stripCrFinalizers() {
              kubectl $RT get "$1" --all-namespaces -o jsonpath='{range .items[?(@.metadata.finalizers)]}{.metadata.namespace}/{.metadata.name}{"\\n"}{end}' 2>/dev/null |
              while read -r ref; do
                ns=${ref%%/*}
                name=${ref#*/}
                echo "cleanupCRDs: removing finalizers from $1 $name${ns:+ (namespace $ns)}"
                kubectl $RT patch "$1" "$name" ${ns:+-n "$ns"} --type=merge -p '{"metadata":{"finalizers":null}}' >/dev/null \\
                  || echo "cleanupCRDs: could not remove finalizers from $1 $name" >&2
              done
            }

            START=$(date +%s)
            ATTEMPT=0
            while :; do
              ALL=$(kubectl $RT get crd -o name) || { echo "cleanupCRDs: cannot list the CRDs (cluster unreachable?) - skipping" >&2; exit 0; }
              REMAINING=$(echo "$ALL" | grep -E "$CRD_PATTERN")
              if [ -z "$REMAINING" ]; then
                echo "cleanupCRDs: all the targeted CRDs are gone"
                exit 0
              fi
              ELAPSED=$(( $(date +%s) - START ))
              if [ "$ELAPSED" -ge "$MAX_WAIT" ]; then
                echo "cleanupCRDs: WARNING - CRDs still present after ${MAX_WAIT}s, giving up on:" >&2
                echo "$REMAINING" >&2
                exit 0
              fi
              ATTEMPT=$((ATTEMPT + 1))
              echo "cleanupCRDs: attempt $ATTEMPT after ${ELAPSED}s - $(echo "$REMAINING" | wc -l | tr -d ' ') CRD(s) to go"
              if [ "$ATTEMPT" -eq 1 ]; then
                echo "$REMAINING"
              fi
              kubectl $RT delete $REMAINING --ignore-not-found --wait=false >/dev/null || true
              for CRD in $REMAINING; do
                stripCrFinalizers "${CRD#*/}"
                if [ "$ELAPSED" -ge "$FINALIZER_GRACE" ]; then
                  echo "cleanupCRDs: $CRD is still present after ${ELAPSED}s - removing its own finalizers"
                  kubectl $RT patch "$CRD" --type=merge -p '{"metadata":{"finalizers":null}}' >/dev/null || true
                fi
              done
              sleep "$INTERVAL"
            done
            """
            .replace("@CRD_PATTERN@", pattern)
            .replace("@GROUPS@", String.join(", ", GROUPS));
    }

    /**
     * {@link #command()} escaped for use as a Terraform provisioner command, where an
     * unescaped {@code ${VAR}} would be parsed as a Terraform interpolation and fail the plan.
     */
    public static @NonNull String provisionerCommand() {
        return escapeTerraformTemplate(command());
    }
}
