package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.spark.SparkWarehouseConfig;
import io.kubernetes.client.openapi.ApiException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.*;

/**
 * This was initially run as a step after its superclass so it could be configured with domain names etc.
 * But now we're more production like (static domain names; persisted Polaris metadata etc)
 * this could be feasibly moved into its parent class.
 */
public class SparkClusterDeployment extends AwsWholeStack {

    static final Logger logger                 = org.slf4j.LoggerFactory.getLogger(SparkClusterDeployment.class);
    static final String NO_SPARK_LB_FINALIZERS = "kubectl patch service spark-master-lb -p '{\"metadata\":{\"finalizers\":null}}'  --type=merge";

    public SparkClusterDeployment(@NotNull Construct scope,
                                  @NotNull SupportedCloudsEnum id,
                                  Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> eksDependOn = new ArrayList<>(cloudProvider.getDependables());
        eksDependOn.add(sparkOperatorConfig.operatorReady);

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
                        /*
                        This barfs with:
│ Error: Provider produced inconsistent result after apply
│
│ When applying changes to kubernetes_manifest.sparkConnectManifest, provider "provider[\"registry.opentofu.org/hashicorp/kubernetes\"]" produced an unexpected new value:
│ .object.metadata.finalizers: was cty.ListValEmpty(cty.String), but now null.
│
│ This is a bug in the provider, which should be reported in the provider's own issue tracker.
                        ,"finalizers", Collections.emptyList() // // sparkapplications.spark.apache.org/finalizer in rel life
                         */
                                  ),
                "spec", Map.of(
                        "runtimeVersions", Map.of(
                                "sparkVersion", "4.0.1"
                                                 ),
                        "mainClass", "org.apache.spark.sql.connect.service.SparkConnectServer",
                        "sparkConf", sparkConfig(SPARK_SERVICE_ACCOUNT_NAME, id),
                        "driverSpec", Map.of(
                                "podTemplateSpec", Map.of(
                                        "spec", Map.of(
                                                "resources", Map.of(
                                                        "limits", Map.of("ephemeralStorage", "16Gi"),
                                                        "requests", Map.of("ephemeralStorage", "8Gi")
                                                                   ),
                                                "restartPolicy", "Always"
                                                      )
                                                         )
                                            )
                              )
                                                               );

        ObjectMapper mapper = new ObjectMapper();
        try {
            String sparkClusterJson    = mapper.writeValueAsString(sparkClusterManifest);
            String sparkConnectJson    = mapper.writeValueAsString(sparkConnectManifest);
            List<LocalExecProvisioner> clusterProvisionersCreate  = uponCreateRun("echo '" + sparkClusterJson + "' | kubectl apply -f -");
            List<LocalExecProvisioner> clusterProvisionersDestroy = uponDestroyRun(
                    "kubectl delete statefulset emrys-master --force --grace-period=0",
                    "kubectl delete statefulset emrys-worker --force --grace-period=0",
                    "kubectl patch sparkclusters emrys  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
            );
            List<LocalExecProvisioner> allClusterProvisioners = new java.util.ArrayList<>();
            allClusterProvisioners.addAll(clusterProvisionersCreate);
            allClusterProvisioners.addAll(clusterProvisionersDestroy);
            Resource.Builder.create(this, "sparkClusterManifest")
                            .provisioners(allClusterProvisioners)
                            .dependsOn(eksDependOn)
                            .build();

            List<LocalExecProvisioner> connectProvisionersCreate  = uponCreateRun("echo '" + sparkConnectJson + "' | kubectl apply -f -");
            List<LocalExecProvisioner> connectProvisionersDestroy = uponDestroyRun(
                    "kubectl patch sparkapplication spark-connector  -p '{\"metadata\":{\"finalizers\":null}}' --type=merge"
            );
            List<LocalExecProvisioner> allConnectProvisioners = new java.util.ArrayList<>();
            allConnectProvisioners.addAll(connectProvisionersCreate);
            allConnectProvisioners.addAll(connectProvisionersDestroy);
            Resource.Builder.create(this, "sparkConnectManifest")
                            .provisioners(allConnectProvisioners)
                            .dependsOn(eksDependOn)
                            .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize manifest to JSON", e);
        }

        Service sparkLB = sparkExternalService(eksDependOn);

        unregisterCRDs(List.of(sparkLB));
    }

    private Service sparkExternalService(List<ITerraformDependable> eksDependOn) {
        String                          subnetIds      = cloudProvider.getSubnetIds().stream().collect(Collectors.joining(","));
        ArrayList<ITerraformDependable> lbDependencies = new ArrayList<>(eksDependOn);
        lbDependencies.add(cloudProvider.loadBalancerRole);
        return Service.Builder.create(this, SPARK_SERVICE_NAME)
                              .metadata(ServiceMetadata.builder()
                                                       .name(SPARK_SERVICE_NAME)
                                                       .labels(Map.of("spark-role", "master",
                                                                      "spark-version", "4.0.1",
                                                                      "spark.operator/name", "spark-kubernetes-operator",
                                                                      "spark.operator/spark-cluster-name", "prod"
                                                                     ))
                                                       .annotations(Map.of(
                                                               "service.beta.kubernetes.io/aws-load-balancer-subnets", subnetIds,
                                                               "service.beta.kubernetes.io/aws-load-balancer-internal", "false"))
                                                       .build())
                              .dependsOn(lbDependencies)
                              .provisioners(uponDestroyRun(NO_SPARK_LB_FINALIZERS))
                              .spec(ServiceSpec.builder()
                                               .selector(Map.of(
                                                       "spark-role", "master"))
                                               .type("LoadBalancer")
                                               .port(List.of(
                                                       ServiceSpecPort.builder()
                                                                      .name("web")
                                                                      .port(8080) // Port the load balancer listens on
                                                                      .targetPort("web") // Port the container is listening on
                                                                      .protocol("TCP")
                                                                      .build(),
                                                       ServiceSpecPort.builder()
                                                                      .name("spark")
                                                                      .port(7077) // Port the load balancer listens on
                                                                      .targetPort("7077") // Port the container is listening on
                                                                      .protocol("TCP")
                                                                      .build(),
                                                       ServiceSpecPort.builder()
                                                                      .name("rest")
                                                                      .port(6066) // Port the load balancer listens on
                                                                      .targetPort("rest") // Port the container is listening on
                                                                      .protocol("TCP")
                                                                      .build()
                                                            ))
                                               .build())
                              .build();
    }

    /**
     * Turned all Spark logging on. Probably too much. But watch the SQL executed with:
     * <code>
     * kubectl logs -f spark-connector-0-driver  | egrep -i 'SparkSqlParser|iceberg'
     * </code>
     */
    private Map<String, String> sparkConfig(String serviceAccountName,
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
            POLARIS_ID.getValueFrom(properties),
            POLARIS_SECRET.getValueFrom(properties),
            AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties)
        );
        for (SupportedCloudsEnum cloudProvider : SupportedCloudsEnum.values()) {
            Map<String, String> configs = sparkWarehouseConfig.sparkConfigs(cloudProvider);
            toReturn.putAll(configs);
        }
        toReturn.putAll(sparkWarehouseConfig.sparkConfigs("federatedCatalogName")); // taken from afon.properties
        return toReturn;
    }

    public static void main(String[] args) {
        try {
            createSparkDeploymentTerraformFile();
        } catch (RuntimeException e) {
            logAndThrow(e);
        }
    }

    static void logAndThrow(RuntimeException e) {
        logger.error(e.toString());
        throw new RuntimeException(e);
    }

    private static void createSparkDeploymentTerraformFile() {
        logger.info("Creating JSON for a Spark cluster");
        final App app = new App();
        new SparkClusterDeployment(app, AwsKubernetesPolaris.ID, new AfonPropertiesLoader().getProperties());
        app.synth();
    }

}
