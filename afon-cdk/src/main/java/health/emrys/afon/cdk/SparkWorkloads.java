package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkHelper.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.cdk.CdkUtils.*;
import static health.emrys.afon.cdk.JupyterHubConfig.USER_POD_LABELS;
import static health.emrys.afon.config.AfonProperty.USER_POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.USER_POLARIS_SECRET;
import static health.emrys.afon.spark.SparkWarehouseConfig.SPARK_IMAGE;
import static java.lang.String.format;

/**
 * Cloud-agnostic application of the Spark operator's custom resources: a {@code SparkCluster}
 * ("emrys") and a {@code SparkApplication} ("spark-connector"). The spark-kubernetes-operator
 * is only a controller — it launches a master/workers and a driver/executors solely in response
 * to these CRs. Installing the operator (see {@link SparkConfig}) without applying these
 * manifests leaves it idle, which is why both the AWS and Azure stacks must call {@link #deploy}.
 */
public class SparkWorkloads {
    public static final String MASTER = "emrys-master";
    public static final Map<String, String> MASTER_LABELS = Map.of(
        "spark-role", "master");
    public static final Map<String, String> DRIVER_LABELS = Map.of(
        "spark-role", "driver");

    private final String polarisDomainName;
    private final AfonPropertyProvider properties;

    public SparkWorkloads(String polarisDomainName, AfonPropertyProvider properties) {
        this.polarisDomainName = polarisDomainName;
        this.properties = properties;
    }

    /**
     * Applies the {@code SparkCluster} and {@code SparkApplication} manifests against the cluster
     * targeted by the active kubeconfig. Resource ids are kept stable ("sparkClusterManifest" /
     * "sparkConnectManifest") so existing stack state is preserved.
     *
     * @param scope     the CDKTF stack the null-provider resources are scoped to.
     * @param id        the cloud the warehouse/Polaris config is derived from.
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
                    "spark.kubernetes.container.image", SPARK_IMAGE,
                    "spark.master.ui.title", "Prod Spark Cluster",
                    "spark.master.rest.enabled", "true",
                    "spark.master.rest.host", "0.0.0.0",
                    "spark.ui.reverseProxy", "true"
                )
            )
        );

        String                     sparkClusterJson       = toJson(sparkClusterManifest);
        List<LocalExecProvisioner> allClusterProvisioners = new ArrayList<>();
        allClusterProvisioners.addAll(uponCreateRun("echo '" + sparkClusterJson + "' | kubectl apply -f -"));
        allClusterProvisioners.addAll(uponDestroyRun(
            "kubectl delete statefulset emrys-master --force --grace-period=0",
            "kubectl delete statefulset emrys-worker --force --grace-period=0",
            "kubectl patch sparkclusters emrys  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
        ));
        Resource.Builder.create(scope, "sparkClusterManifest")
                        .provisioners(allClusterProvisioners)
                        .dependsOn(dependsOn)
                        .build();

        List<LocalExecProvisioner> allConnectProvisioners = new ArrayList<>();
        allConnectProvisioners.addAll(uponCreateRun(startConnectCommand()));
        allConnectProvisioners.addAll(uponDestroyRun(
            "kubectl patch sparkapplication spark-connector  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
        ));
        Resource.Builder.create(scope, "sparkConnectManifest")
                        .provisioners(allConnectProvisioners)
                        .dependsOn(dependsOn)
                        .build();
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
                "sparkConf", sparkConfig(polarisDomainName),
                "driverSpec", Map.of(
                    "podTemplateSpec", Map.of(
                        // app.kubernetes.io/name is the canonical label Hubble UI
                        // reads to title flows; without it the driver/executor
                        // pods show as "Unknown App".
                        "metadata", Map.of(
                            "labels", addMaps(Map.of(
                                "app.kubernetes.io/name", SPARK_CONNECTOR_NAME + "-driver"
                            ), USER_POD_LABELS)
                        ),
                        "spec", Map.of(
                            "restartPolicy", "Always"
                        )
                    )
                ),
                "executorSpec", Map.of(
                    "podTemplateSpec", Map.of(
                        "metadata", Map.of(
                            "labels", addMaps(Map.of(
                                "app.kubernetes.io/name", SPARK_CONNECTOR_NAME + "-executor"
                            ), USER_POD_LABELS)
                        )
                    )
                )
            )
        );

        String sparkConnectJson = toJson(sparkConnectManifest);
        return "echo '" + sparkConnectJson + "' | kubectl apply -f -";
    }

    private static String toJson(Map<String, Object> map) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize manifest to JSON", e);
        }
    }

    /**
     * Turned all Spark logging on. Probably too much. But watch the SQL executed with:
     * <code>
     * kubectl logs -f spark-connector-0-driver  | egrep -i 'SparkSqlParser|iceberg'
     * </code>
     */
    private Map<String, String> sparkConfig(String polarisDomainName) {
        var first = Map.of(
            "spark.kubernetes.container.image", SPARK_IMAGE,
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
// These are not necessary if you use ph1ll1phenry/spark_jars:4.0.1
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
        try {
            Map<String, String> config = sparkCloudConfig(polarisDomainName);
            config.putAll(first);
            config.putAll(second);
            return config;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private @NonNull Map<String, String> sparkCloudConfig(String polarisDomainName) {
        var config = new HashMap<String, String>();
        config.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        config.putAll(userSparkWarehouseConfig(polarisDomainName));
        return config;
    }

    private Map<String, String> userSparkWarehouseConfig(String polarisDomainName) {
        String clientId = properties.getValue(USER_POLARIS_ID);
        String clientSecret = properties.getValue(USER_POLARIS_SECRET);
        SparkWarehouseConfig sparkWarehouseConfig = SparkWarehouseConfigFactory.getFactory().createConfig(properties, polarisDomainName, clientId, clientSecret);
        return sparkWarehouseConfig.allSparkConfigs("federatedCatalogName"); // taken from afon.properties
    }
}
