package health.emrys.afon.cdk.configs;

import health.emrys.afon.cdk.DockerImages;
import health.emrys.afon.cdk.KubeConfigAspect;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonCloud;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkHelper.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.cdk.CdkUtils.*;
import static java.lang.String.format;

/**
 * Cloud-agnostic application of the Spark operator's custom resources: a {@code SparkCluster}
 * ("emrys") and a {@code SparkApplication} ("spark-connector"). The spark-kubernetes-operator
 * is only a controller — it launches a master/workers and a driver/executors solely in response
 * to these CRs. Installing the operator (see {@link SparkConfig}) without applying these
 * manifests leaves it idle, which is why both the AWS and Azure stacks must call {@link #deploy}.
 *
 * <p>The Spark Connect server is shared by all users, so it registers no Iceberg catalogs and holds
 * no Polaris credentials: each user declares their catalogs in their own session, authenticating
 * with their own OIDC token (see {@code SparkWarehouseConfig#sparkConfigsWithToken}).
 */
public class SparkWorkloads {
    public static final String MASTER = "emrys-master";
    /** Logs the user and SQL of each query against an Iceberg table; it is in the Spark image, not on this classpath. */
    static final String AUDIT_LISTENER = "health.emrys.afon.spark.audit.AuditQueryListener";
    /** How long the Spark Connect driver waits for the master to be Ready before the deploy fails. */
    static final int MASTER_WAIT_SECONDS = 600;
    public static final Map<String, String> MASTER_LABELS = Map.of(
        "spark-role", "master");
    public static final Map<String, String> DRIVER_LABELS = Map.of(
        "spark-role", "driver");

    /**
     * Applies the {@code SparkCluster} and {@code SparkApplication} manifests against the cluster
     * targeted by the kubeconfig in {@code KUBECONFIG}, on AWS the stack's own, see {@link KubeConfigAspect}. Resource ids are kept stable ("sparkClusterManifest" /
     * "sparkConnectManifest") so existing stack state is preserved.
     *
     * @param scope     the CDKTF stack the null-provider resources are scoped to.
     * @param id        the cloud the stack is deployed to.
     * @param dependsOn must include the operator-ready signal (so the CRDs exist) and the
     *                  cluster's own dependables.
     */
    public void deploy(
        @NonNull Construct scope,
        @NonNull AfonCloud id,
        @NonNull List<? extends ITerraformDependable> dependsOn) {
        final Map<String, Object> sparkClusterManifest = Map.of(
            "apiVersion", "spark.apache.org/v1alpha1",
            "kind", "SparkCluster",
            "metadata", Map.of(
                "name", "emrys",
                "namespace", "default"
            ),
            "spec", Map.of(
                "runtimeVersions", Map.of(
                    "sparkVersion", "4.0.1"
                ),
                "clusterTolerations", Map.of(
                    "instanceConfig", Map.of(
                        "initWorkers", 2, // Integer type
                        "minWorkers", 2, // Integer type
                        "maxWorkers", 2  // Integer type
                    )
                ),
                "sparkConf", Map.of(
                    "spark.kubernetes.container.image", DockerImages.SPARK_IMAGE,
                    "spark.master.ui.title", "Prod Spark Cluster",
                    "spark.master.rest.enabled", "true",
                    "spark.master.rest.host", "0.0.0.0",
                    "spark.ui.reverseProxy", "true"
                )
            )
        );

        String sparkClusterJson = toCanonicalJson(sparkClusterManifest);
        List<LocalExecProvisioner> allClusterProvisioners = new ArrayList<>();
        allClusterProvisioners.addAll(uponCreateRun("echo '" + sparkClusterJson + "' | kubectl apply -f -"));
        allClusterProvisioners.addAll(uponDestroyRun(
            "kubectl delete statefulset emrys-master --force --grace-period=0",
            "kubectl delete statefulset emrys-worker --force --grace-period=0",
            "kubectl patch sparkclusters emrys  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
        ));
        Resource sparkCluster = Resource.Builder.create(scope, "sparkClusterManifest")
                        .provisioners(allClusterProvisioners)
                        .dependsOn(dependsOn)
                        .build();

        // The driver gives up registering with the master after about a minute, and the master can take longer than that to be
        // Ready on a cold node, which pulls its image first, so the driver is only started once the master is.
        List<ITerraformDependable> connectDependsOn = new ArrayList<>(dependsOn);
        connectDependsOn.add(sparkCluster);
        List<LocalExecProvisioner> allConnectProvisioners = new ArrayList<>();
        allConnectProvisioners.addAll(uponCreateRun(waitForMasterCommand(), startConnectCommand()));
        allConnectProvisioners.addAll(uponDestroyRun(
            "kubectl patch sparkapplication spark-connector  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
        ));
        Resource.Builder.create(scope, "sparkConnectManifest")
                        .provisioners(allConnectProvisioners)
                        .dependsOn(connectDependsOn)
                        .build();
    }

    /**
     * @return the command that waits until the pod of the Spark master exists, which the operator creates in response to the
     * {@code SparkCluster}, and is Ready, which is when its headless service starts to resolve; it fails after
     * {@value #MASTER_WAIT_SECONDS}s, saying why, rather than leaving a driver that cannot register
     */
    public String waitForMasterCommand() {
        String pod = MASTER + "-0";
        return format(
            "for i in $(seq 1 %d); do kubectl get pod %s >/dev/null 2>&1 && break; sleep 5; done; "
            + "kubectl wait --for=condition=Ready pod/%s --timeout=%ds "
            + "|| { echo 'Spark master %s is not Ready, so the Spark Connect driver would not find it' >&2; exit 1; }",
            MASTER_WAIT_SECONDS / 5, pod, pod, MASTER_WAIT_SECONDS, pod);
    }

    public String startConnectCommand() {
        final Map<String, Object> sparkConnectManifest = Map.of(
            "apiVersion", "spark.apache.org/v1",
            "kind", "SparkApplication",
            "metadata", Map.of(
                "name", SPARK_CONNECTOR_NAME,
                "namespace", "default"
            ),
            "spec", Map.of(
                "runtimeVersions", Map.of(
                    "sparkVersion", "4.0.1"
                ),
                "mainClass", "org.apache.spark.sql.connect.service.SparkConnectServer",
                "sparkConf", sparkConfig(),
                "driverSpec", Map.of(
                    "podTemplateSpec", Map.of(
                        // app.kubernetes.io/name is the canonical label Hubble UI
                        // reads to title flows; without it the driver/executor
                        // pods show as "Unknown App".
                        "metadata", Map.of(
                            "labels", Map.of(
                                "app.kubernetes.io/name", SPARK_CONNECTOR_NAME + "-driver"
                            )
                        ),
                        "spec", Map.of(
                            "restartPolicy", "Always"
                        )
                    )
                ),
                "executorSpec", Map.of(
                    "podTemplateSpec", Map.of(
                        "metadata", Map.of(
                            "labels", Map.of(
                                "app.kubernetes.io/name", SPARK_CONNECTOR_NAME + "-executor"
                            )
                        )
                    )
                )
            )
        );

        String sparkConnectJson = toCanonicalJson(sparkConnectManifest);
        return "echo '" + sparkConnectJson + "' | kubectl apply -f -";
    }

    /**
     * Turned all Spark logging on. Probably too much. But watch who ran what against the Iceberg tables with:
     * <code>
     * kubectl logs -f spark-connector-0-driver | grep health.emrys.afon.spark.audit
     * </code>
     * The {@code AuditQueryListener} that logs it is in the Spark image, see scripts/docker/spark-connect/Dockerfile.
     */
    private Map<String, String> sparkConfig() {
        var first = Map.of(
            "spark.kubernetes.container.image", DockerImages.SPARK_IMAGE,
            "spark.master.ui.title", "Prod Spark Cluster",
            "spark.master.rest.enabled", "true",
            "spark.master.rest.host", "0.0.0.0",
            "spark.ui.reverseProxy", "true",
            "spark.master", format("spark://%s-svc:7077", MASTER),
            "spark.submit.deployMode", "cluster",
            "spark.executor.cores", "1",
            "spark.cores.max", "3",
            "spark.kubernetes.authenticate.driver.serviceAccountName", SPARK_SERVICE_ACCOUNT_NAME
        );
        var second = Map.of(
            "spark.driver.allowMultipleContexts", "true",
            "spark.sql.iceberg.planning.preserve-data-grouping", "true",
            "spark.sql.sources.v2.bucketing.enabled", "true",
            "spark.sql.sources.bucketing.enabled", "true",
// These are not necessary if you use emryshealth/spark_jars:4.0.1
//            "spark.jars.packages", "com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.2,com.google" +
//                                   ".cloud:google-cloud-storage:2.57.0,software.amazon.awssdk:bundle:2.34.6,org" +
//                                   ".apache.iceberg:iceberg-spark-runtime-4.0_2.13:1.10.0,org.apache" +
//                                   ".hadoop:hadoop-azure:3.3.4,com.azure:azure-storage-file-datalake:12.22.0",
            "spark.driver.userClassPathFirst", "true",
            "spark.executor.userClassPathFirst", "true",
            "spark.log.level", "DEBUG",
//            "spark.connect.grpc.interceptor.classes", "org.apache.spark.sql.connect.service.LoggingInterceptor", // No! this logs patient data too!
            "spark.driver.extraJavaOptions", "-Divy.home=/opt/spark/"
        );
        Map<String, String> config = new HashMap<>();
        config.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        config.put("spark.sql.queryExecutionListeners", AUDIT_LISTENER);
        config.putAll(first);
        config.putAll(second);
        return config;
    }
}
