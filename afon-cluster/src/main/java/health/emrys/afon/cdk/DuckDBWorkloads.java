package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.duckdb.DuckDbConfig.DUCKDB_IMAGE;
import static health.emrys.afon.duckdb.DuckDbConfig.KEEP_ALIVE;
import static health.emrys.afon.cluster.Defaults.DUCKDB_POD_NAME;

/**
 * Cloud-agnostic deployment of a DuckDB pod — the counterpart to {@link SparkWorkloads} and
 * {@link TrinoWorkloads}. Unlike Trino (which ships an official Helm chart) or Spark (which has a
 * Kubernetes operator), DuckDB is an in-process engine with no server, operator, or first-party
 * chart. There is therefore nothing to install and nothing to reconcile: the maintained
 * deployment path is simply a long-lived pod running the DuckDB CLI, which users reach with
 * {@code kubectl exec}. This mirrors how {@link SparkWorkloads} applies its manifests — a
 * null-provider {@link Resource} whose provisioners {@code kubectl apply} on create and
 * {@code kubectl delete} on destroy — rather than the Helm {@link com.hashicorp.cdktf.providers.helm.release.Release}
 * path {@link TrinoWorkloads} uses.
 *
 * <p>Because DuckDB holds its database in a single file, the pod mounts an {@code emptyDir} at
 * {@code /data} to give the CLI a writable working directory; nothing here is cloud-specific, so
 * (like Spark's manifests) the same pod is applied for every {@link SupportedCloudsEnum}.
 */
public class DuckDBWorkloads {



    /**
     * Applies the DuckDB pod manifest against the cluster targeted by the active kubeconfig. The
     * resource id and pod name are kept stable ("duckdb") so existing stack state is preserved.
     *
     * @param scope     the CDKTF stack the null-provider resource is scoped to.
     * @param id        the cloud this stack targets; DuckDB is cloud-agnostic, so it is accepted
     *                  only to keep the signature aligned with {@link SparkWorkloads#deploy}.
     * @param dependsOn must include the cluster's dependables (so the API server is reachable).
     */
    public void deploy(@NotNull Construct scope,
                       @NotNull SupportedCloudsEnum id,
                       @NotNull List<? extends ITerraformDependable> dependsOn) {
        final Map<String, Object> duckDbPodManifest = Map.of(
            "apiVersion", "v1",
            "kind", "Pod",
            "metadata", Map.of(
                "name", DUCKDB_POD_NAME,
                "namespace", "default",
                // app.kubernetes.io/name is the canonical label the Hubble UI reads to title
                // flows; kept consistent with the Spark driver/executor pods.
                "labels", Map.of("app.kubernetes.io/name", DUCKDB_POD_NAME)
            ),
            "spec", Map.of(
                "restartPolicy", "Always",
                "containers", List.of(Map.of(
                    "name", DUCKDB_POD_NAME,
                    "image", DUCKDB_IMAGE,
                    "command", KEEP_ALIVE,
                    "workingDir", "/data",
                    "volumeMounts", List.of(Map.of(
                        "name", "duckdb-data",
                        "mountPath", "/data"
                    ))
                )),
                "volumes", List.of(Map.of(
                    "name", "duckdb-data",
                    "emptyDir", Map.of()
                ))
            )
        );

        String                     duckDbPodJson = toJson(duckDbPodManifest);
        List<LocalExecProvisioner> provisioners  = new ArrayList<>();
        provisioners.addAll(uponCreateRun("echo '" + duckDbPodJson + "' | kubectl apply -f -"));
        provisioners.addAll(uponDestroyRun(
            "kubectl delete pod " + DUCKDB_POD_NAME + " --force --grace-period=0"
        ));
        Resource.Builder.create(scope, "duckDbPodManifest")
                        .provisioners(provisioners)
                        .dependsOn(dependsOn)
                        .build();
    }

    private static String toJson(Map<String, Object> map) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DuckDB manifest to JSON", e);
        }
    }
}