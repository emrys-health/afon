package health.emrys.afon.cdk.configs;

import health.emrys.afon.cdk.DockerImageResolver;
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

import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.cdk.CdkUtils.*;
import static java.lang.String.format;

/**
 * Cloud-agnostic application of the Spark operator's custom resources: a {@code SparkCluster}
 * (named by {@link SparkNames}) and a {@code SparkApplication} ("spark-connector"). The spark-kubernetes-operator
 * is only a controller — it launches a master/workers and a driver/executors solely in response
 * to these CRs. Installing the operator (see {@link SparkConfig}) without applying these
 * manifests leaves it idle, which is why both the AWS and Azure stacks must call {@link #deploy}.
 *
 * <p>The resources applying them are triggered by the name of the cluster, so that renaming it replaces them: the old
 * cluster is deleted, by the name it was created with, then the new one is created.
 *
 * <p>The Spark Connect server is shared by all users, so it registers no Iceberg catalogs and holds
 * no Polaris credentials: each user declares their catalogs in their own session, authenticating
 * with their own OIDC token (see {@code SparkWarehouseConfig#sparkConfigsWithToken}).
 */
public class SparkWorkloads {
    /** Name of the {@code SparkApplication} serving Spark Connect, which the network policies select its pods by. */
    public static final String SPARK_CONNECTOR_NAME = "spark-connector";
    /** The trigger holding the name of the {@code SparkCluster}, which replaces the resources applying it when it changes. */
    private static final String CLUSTER_NAME_TRIGGER = "clusterName";
    /**
     * The name of the cluster for the destroy-time commands, which may only refer to the resource itself: the one it was created
     * with, not the one now configured, which is what is replaced.  A stack created before the trigger existed has none, hence
     * the fallback to the name it was then given.
     */
    static final String EXISTING_CLUSTER = "${try(self.triggers." + CLUSTER_NAME_TRIGGER + ", \"" + SparkNames.LEGACY_CLUSTER_NAME + "\")}";
    /** Logs the user and SQL of each query against an Iceberg table; it is in the Spark image, not on this classpath. */
    static final String AUDIT_LISTENER = "health.emrys.afon.spark.audit.AuditQueryListener";
    /** How long the Spark Connect driver waits for the master to be Ready before the deploy fails. */
    static final int MASTER_WAIT_SECONDS = 600;
    public static final Map<String, String> MASTER_LABELS = Map.of(
        "spark-role", "master");
    public static final Map<String, String> DRIVER_LABELS = Map.of(
        "spark-role", "driver");
    /**
     * The pods of the workers, which the executors, and so the users' code, run in, the cluster being a standalone one.
     * Note: these values are from the Helm chart.
     * */
    public static final Map<String, String> WORKER_LABELS = Map.of(
        "spark-role", "worker");

    private final SparkNames          names;
    private final DockerImageResolver images;

    public SparkWorkloads(SparkNames names, DockerImageResolver images) {
        this.names  = names;
        this.images = images;
    }

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
                "name", names.clusterName(),
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
                "sparkConf", masterConfig()
            )
        );

        String sparkClusterJson = toCanonicalJson(sparkClusterManifest);
        List<LocalExecProvisioner> allClusterProvisioners = new ArrayList<>();
        allClusterProvisioners.addAll(uponCreateRun("echo '" + sparkClusterJson + "' | kubectl apply -f -"));
        allClusterProvisioners.addAll(uponDestroyRun(
            "kubectl delete sparkcluster " + EXISTING_CLUSTER + " --ignore-not-found --wait=false",
            "kubectl delete statefulset " + EXISTING_CLUSTER + "-master --force --grace-period=0",
            "kubectl delete statefulset " + EXISTING_CLUSTER + "-worker --force --grace-period=0",
            "kubectl patch sparkclusters " + EXISTING_CLUSTER + "  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
        ));
        Resource sparkCluster = Resource.Builder.create(scope, "sparkClusterManifest")
                        .provisioners(allClusterProvisioners)
                        .triggers(Map.of(CLUSTER_NAME_TRIGGER, names.clusterName()))
                        .dependsOn(dependsOn)
                        .build();

        // The driver gives up registering with the master after about a minute, and the master can take longer than that to be
        // Ready on a cold node, which pulls its image first, so the driver is only started once the master is.
        List<ITerraformDependable> connectDependsOn = new ArrayList<>(dependsOn);
        connectDependsOn.add(sparkCluster);
        List<LocalExecProvisioner> allConnectProvisioners = new ArrayList<>();
        allConnectProvisioners.addAll(uponCreateRun(waitForMasterCommand(), startConnectCommand()));
        allConnectProvisioners.addAll(uponDestroyRun(
            "kubectl delete sparkapplication " + SPARK_CONNECTOR_NAME + " --ignore-not-found --wait=false",
            "kubectl patch sparkapplication " + SPARK_CONNECTOR_NAME + "  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
        ));
        Resource.Builder.create(scope, "sparkConnectManifest")
                        .provisioners(allConnectProvisioners)
                        .triggers(Map.of(CLUSTER_NAME_TRIGGER, names.clusterName()))
                        .dependsOn(connectDependsOn)
                        .build();
    }

    /**
     * @return the command that waits until the pod of the Spark master exists, which the operator creates in response to the
     * {@code SparkCluster}, and is Ready, which is when its headless service starts to resolve; it fails after
     * {@value #MASTER_WAIT_SECONDS}s, saying why, rather than leaving a driver that cannot register
     */
    public String waitForMasterCommand() {
        String pod = names.masterPod();
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
     * The configuration of the master of the {@code SparkCluster}.  Its REST submission server is off: it takes, with no
     * authentication, an application to run on the workers, beside every user's tasks, or the id of one to kill, and nothing
     * here submits to it, the operator creating the driver of Spark Connect as a pod of its own, which registers with the
     * master on 7077.  Its UI does not proxy to those of the drivers and the workers, which would show every user's queries
     * and executors' logs to whoever reaches it; an administrator reaches each with {@code kubectl port-forward}.
     */
    Map<String, String> masterConfig() {
        return Map.of(
            "spark.kubernetes.container.image", images.spark(),
            "spark.master.ui.title", "Prod Spark Cluster",
            "spark.master.rest.enabled", "false",
            "spark.ui.reverseProxy", "false");
    }

    /**
     * What keeps the users of the one Spark Connect server, whose code runs in the same driver and executors, from each other's
     * work, as far as settings can; only a server of their own does so wholly:
     * <ul>
     *   <li>no UI on the driver: every pod of a worker, where the users' code runs, may reach the driver on any port, and the UI
     *       shows every session's queries and plans, with the values in them;</li>
     *   <li>the shuffle and spill files and the cached blocks that the executors write to their local disks are encrypted, with a
     *       key of the application's, so that what a user's code finds there is not another's data;</li>
     *   <li>a Python worker serves one task and ends, so that no user's code is left behind in it to run with another's rows;</li>
     *   <li>the jars that Spark ships come before the user's, so that no class of theirs takes the place of one that every session
     *       runs, such as Iceberg's;</li>
     *   <li>the sessions' jobs share the executors fairly, rather than in the order that they come, so that no user's job holds
     *       every core while another's waits;</li>
     *   <li>no result to collect larger than half the driver's default memory, which would end every user's session with it.</li>
     * </ul>
     */
    static Map<String, String> isolationConfig() {
        return Map.of(
            "spark.ui.enabled", "false",
            "spark.io.encryption.enabled", "true",
            "spark.python.worker.reuse", "false",
            "spark.driver.userClassPathFirst", "false",
            "spark.executor.userClassPathFirst", "false",
            "spark.scheduler.mode", "FAIR",
            "spark.driver.maxResultSize", "512m");
    }

    /**
     * Logs at INFO, as DEBUG wrote the data and plans of every user's tasks to the logs of the executors, which their code may read.
     * Watch who ran what against the Iceberg tables with:
     * <code>
     * kubectl logs -f spark-connector-0-driver | grep health.emrys.afon.spark.audit
     * </code>
     * The {@code AuditQueryListener} that logs it is in the Spark image, see scripts/docker/spark-connect/Dockerfile.
     */
    private Map<String, String> sparkConfig() {
        var first = Map.of(
            "spark.kubernetes.container.image", images.spark(),
            "spark.master.ui.title", "Prod Spark Cluster",
            "spark.ui.reverseProxy", "false",
            "spark.master", format("spark://%s:7077", names.masterService()),
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
            "spark.log.level", "INFO",
//            "spark.connect.grpc.interceptor.classes", "org.apache.spark.sql.connect.service.LoggingInterceptor", // No! this logs patient data too!
            "spark.driver.extraJavaOptions", "-Divy.home=/opt/spark/"
        );
        Map<String, String> config = new HashMap<>();
        config.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        config.put("spark.sql.queryExecutionListeners", AUDIT_LISTENER);
        config.putAll(first);
        config.putAll(second);
        config.putAll(isolationConfig());
        return config;
    }
}
