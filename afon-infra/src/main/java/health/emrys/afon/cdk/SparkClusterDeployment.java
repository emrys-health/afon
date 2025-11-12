package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.manifest.Manifest;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.core.configuration.SparkContextConfigurationPrinter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.*;

public class SparkClusterDeployment extends WholeStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkClusterDeployment.class);

    public SparkClusterDeployment(@NotNull Construct scope, @NotNull String id) {
        super(scope, id);
        String sparkConnectorName = SPARK_CONNECTOR_NAME;
        String sparkServiceAccountName = SPARK_SERVICE_ACCOUNT_NAME;
        List<Resource> eksDependOn = List.of();

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
                        "name", sparkConnectorName,
                        "namespace", "default"
                                  ),
                "spec", Map.of(
                        "runtimeVersions", Map.of(
                                "sparkVersion", "4.0.1"
                                                 ),
                        "mainClass", "org.apache.spark.sql.connect.service.SparkConnectServer",
                        "sparkConf", sparkConfig(SPARK_SERVICE_ACCOUNT_NAME),
                        "driverSpec", Map.of(
                                "podTemplateSpec", Map.of(
                                        "spec", Map.of()
                                                         )
                                            )
                              )
                                                               );

        Manifest.Builder.create(this, "sparkClusterManifest")
                        .manifest(sparkClusterManifest)
                        .dependsOn(eksDependOn)
                        .build();
        Manifest.Builder.create(this, "sparkConnectManifest")
                        .manifest(sparkConnectManifest)
                        .dependsOn(eksDependOn)
                        .build();

        Service.Builder.create(this, SPARK_SERVICE_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(SPARK_SERVICE_NAME)
                                                .labels(Map.of("spark-role", "master",
                                                               "spark-version", "4.0.1",
                                                               "spark.operator/name", "spark-kubernetes-operator",
                                                               "spark.operator/spark-cluster-name", "prod")
                                                       )
                                                .build())
                       .dependsOn(eksDependOn)
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

    private Map<String, String> sparkConfig(String serviceAccountName) {
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
                "spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
                "spark.driver.allowMultipleContexts", "true",
                "spark.sql.iceberg.planning.preserve-data-grouping", "true",
                "spark.sql.sources.v2.bucketing.enabled", "true",
                "spark.sql.sources.bucketing.enabled", "true",
                "spark.jars.packages", "com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.2,com.google.cloud:google-cloud-storage:2.57.0,software.amazon.awssdk:bundle:2.34.6,org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:1.10.0",
                "spark.driver.userClassPathFirst", "true",
                "spark.executor.userClassPathFirst", "true",
                "spark.driver.extraJavaOptions", "-Divy.home=/opt/spark/"
                           );
        var toReturn = new HashMap<String, String>();
        try {
            SparkContextConfigurationPrinter app           = SparkContextConfigurationPrinter.createSparkConf();
            var                              polarisConfig = app.getPolarisConfig();
            for (AfonProperties.SupportedCloudsEnum cloudProvider : AfonProperties.SupportedCloudsEnum.values()) {
                Map<String, String> configs = polarisConfig.sparkConfigs(cloudProvider);
                for (Map.Entry<String, String> entry : configs.entrySet()) {
                    toReturn.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        toReturn.putAll(first);
        toReturn.putAll(second);
        return toReturn;
    }

    public static void main(String[] args) {
        logger.info("Creating JSON for a Spark cluster");
        final App app = new App();
        new SparkClusterDeployment(app, AwsKubernetesPolaris.ID);
        app.synth();
    }

}
