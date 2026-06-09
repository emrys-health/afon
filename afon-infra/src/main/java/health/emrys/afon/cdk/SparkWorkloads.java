package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.spark.SparkWarehouseConfig;
import io.kubernetes.client.openapi.ApiException;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.infra.Defaults.SPARK_SERVICE_ACCOUNT_NAME;

/**
 * Cloud-agnostic application of the Spark operator's custom resources: a {@code SparkCluster}
 * ("emrys") and a {@code SparkApplication} ("spark-connector"). The spark-kubernetes-operator
 * is only a controller — it launches a master/workers and a driver/executors solely in response
 * to these CRs. Installing the operator (see {@link SparkConfig}) without applying these
 * manifests leaves it idle, which is why both the AWS and Azure stacks must call {@link #deploy}.
 */
public class SparkWorkloads {

    private SparkWorkloads() {
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
    public static void deploy(@NotNull Construct scope,
                              @NotNull SupportedCloudsEnum id,
                              @NotNull List<? extends ITerraformDependable> dependsOn) {
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
                                "spark.kubernetes.container.image", "apache/spark:4.0.1",
                                "spark.master.ui.title", "Prod Spark Cluster",
                                "spark.master.rest.enabled", "true",
                                "spark.master.rest.host", "0.0.0.0",
                                "spark.ui.reverseProxy", "true"
                                           )
                              )
                                                               );

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
                        "sparkConf", sparkConfig(SPARK_SERVICE_ACCOUNT_NAME, id),
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

        ObjectMapper mapper = new ObjectMapper();
        try {
            String sparkClusterJson = mapper.writeValueAsString(sparkClusterManifest);
            String sparkConnectJson = mapper.writeValueAsString(sparkConnectManifest);

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
            allConnectProvisioners.addAll(uponCreateRun("echo '" + sparkConnectJson + "' | kubectl apply -f -"));
            allConnectProvisioners.addAll(uponDestroyRun(
                    "kubectl patch sparkapplication spark-connector  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
            ));
            Resource.Builder.create(scope, "sparkConnectManifest")
                            .provisioners(allConnectProvisioners)
                            .dependsOn(dependsOn)
                            .build();
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
    private static Map<String, String> sparkConfig(String serviceAccountName,
                                                    SupportedCloudsEnum id) {
        var first = Map.of(
                "spark.kubernetes.container.image", "apache/spark:4.0.1",
                "spark.master.ui.title", "Prod Spark Cluster",
                "spark.master.rest.enabled", "true",
                "spark.master.rest.host", "0.0.0.0",
                "spark.ui.reverseProxy", "true",
                "spark.master", "spark://emrys-master-svc:7077",
                "spark.submit.deployMode", "cluster",
                "spark.executor.cores", "1",
                "spark.cores.max", "3",
                "spark.kubernetes.authenticate.driver.serviceAccountName", serviceAccountName
                          );
        var second = Map.of(
                "spark.driver.allowMultipleContexts", "true",
                "spark.sql.iceberg.planning.preserve-data-grouping", "true",
                "spark.sql.sources.v2.bucketing.enabled", "true",
                "spark.sql.sources.bucketing.enabled", "true",
                "spark.jars.packages", "com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.2,com.google.cloud:google-cloud-storage:2.57.0,software.amazon.awssdk:bundle:2.34.6,org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:1.10.0,org.apache.hadoop:hadoop-azure:3.3.4,com.azure:azure-storage-file-datalake:12.22.0",
                "spark.driver.userClassPathFirst", "true",
                "spark.executor.userClassPathFirst", "true",
                "spark.log.level", "DEBUG",
                "spark.driver.extraJavaOptions", "-Divy.home=/opt/spark/"
                           );
        try {
            Map<String, String> toReturn = sparkCloudProperties(id);
            toReturn.putAll(first);
            toReturn.putAll(second);
            return toReturn;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static @NotNull Map<String, String> sparkCloudProperties(SupportedCloudsEnum id) throws IncorrectConfigException, IOException, ApiException {
        var toReturn = new HashMap<String, String>();
        toReturn.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        Properties properties = new AfonPropertiesLoader().getProperties();
        SparkWarehouseConfig sparkWarehouseConfig = new SparkWarehouseConfig(
            USER_POLARIS_ID.getValueFrom(properties),
            USER_POLARIS_SECRET.getValueFrom(properties),
            AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties)
        );
        for (SupportedCloudsEnum cloudProvider : SupportedCloudsEnum.values()) {
            Map<String, String> configs = sparkWarehouseConfig.sparkConfigs(cloudProvider);
            toReturn.putAll(configs);
        }
        toReturn.putAll(sparkWarehouseConfig.sparkConfigs("federatedCatalogName")); // taken from afon.properties
        return toReturn;
    }
}