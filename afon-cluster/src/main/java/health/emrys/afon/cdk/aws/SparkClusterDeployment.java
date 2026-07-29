package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.config.AfonProperties.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cluster.Defaults.SPARK_SERVICE_NAME;

/**
 * This was initially run as a step after its superclass so it could be configured with domain names etc.
 * But now we're more production like (static domain names; persisted Polaris metadata etc)
 * this could be feasibly moved into its parent class.
 */
public class SparkClusterDeployment extends AwsWholeStack {

    static final Logger logger                 = org.slf4j.LoggerFactory.getLogger(SparkClusterDeployment.class);
    static final String NO_SPARK_LB_FINALIZERS = "kubectl patch service spark-master-lb -p '{\"metadata\":{\"finalizers\":null}}'  --type=merge";
    protected final String awsPolarisDomainName;

    public SparkClusterDeployment(@NonNull Construct scope,
                                  @NonNull SupportedCloudsEnum id,
                                  Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> eksDependOn = new ArrayList<>(cloudProvider.getDependables());
        eksDependOn.add(sparkOperatorConfig.operatorReady);
        awsPolarisDomainName = AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties);
        new SparkWorkloads(awsPolarisDomainName).deploy(this, id, eksDependOn);
        Service sparkLB = sparkExternalService(eksDependOn);
        unregisterCRDs(List.of(sparkLB));
    }

    private Service sparkExternalService(List<ITerraformDependable> eksDependOn) {
        String                          subnetIds      = cloudProvider.getSubnetIds().stream().collect(Collectors.joining(","));
        ArrayList<ITerraformDependable> lbDependencies = new ArrayList<>(eksDependOn);
        lbDependencies.add(cloudProvider.loadBalancerRole);
        // Creating this LoadBalancer Service fires the AWS LB Controller's
        // mutating webhook (mservice.elbv2.k8s.aws). Depend on the controller's
        // Helm release — which is installed atomically, so Terraform waits for
        // its pods to be Ready — otherwise the webhook Service can still have
        // zero endpoints and the apply fails with "no endpoints available for
        // service aws-load-balancer-webhook-service".
        lbDependencies.add(cloudProvider.getLbRelease());
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

    public static void main(String[] args) {
        try {
            createSparkDeploymentTerraformFile();
        } catch (RuntimeException e) {
            logAndThrow(e);
        }
    }

    public static void logAndThrow(RuntimeException e) {
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
