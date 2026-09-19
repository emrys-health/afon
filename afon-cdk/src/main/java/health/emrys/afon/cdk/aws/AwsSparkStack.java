package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.NO_SPARK_LB_FINALIZERS;
import static health.emrys.afon.cdk.CdkUtils.addMaps;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_NAME;
import static health.emrys.afon.cdk.SparkWorkloads.MASTER_LABELS;
import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_DOMAIN_NAME;

/**
 * This was initially run as a step after its superclass so it could be configured with domain names etc.
 * But now we're more production like (static domain names; persisted Polaris metadata etc)
 * this could be feasibly moved into its parent class.
 */
public class AwsSparkStack extends AwsJupyterStack {

    private final static Logger              logger = org.slf4j.LoggerFactory.getLogger(AwsSparkStack.class);

    protected final      String              awsPolarisDomainName;

    public AwsSparkStack(@NonNull Construct scope,
                         @NonNull AfonCloud id,
                         AfonPropertyProvider properties) {
        super(scope, id, properties);
        List<ITerraformDependable> eksDependOn = new ArrayList<>(cloudProvider.getDependables());
        eksDependOn.add(sparkOperatorConfig.operatorReady);
        awsPolarisDomainName = properties.getValue(AWS_POLARIS_DOMAIN_NAME);
        new SparkWorkloads(awsPolarisDomainName, properties).deploy(this, id, eksDependOn);
        sparkExternalService(eksDependOn);
        // Cluster-level dependencies only (not the Spark LB): the monitoring releases depend on
        // this resource (see AwsFullStack), so anything more would also serialise their install
        // behind the Spark load balancer.
        unregisterCRDs(cloudProvider.getDependables());
    }

    private Service sparkExternalService(List<ITerraformDependable> eksDependOn) {
        String subnetIds = cloudProvider.getSubnetIds().stream().collect(Collectors.joining(","));
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
                                               .labels(addMaps(MASTER_LABELS, Map.of(
                                                              "spark-version", "4.0.1",
                                                              "spark.operator/name", "spark-kubernetes-operator",
                                                              "spark.operator/spark-cluster-name", "prod"
                                                             )))
                                               .annotations(Map.of(
                                                       "service.beta.kubernetes.io/aws-load-balancer-subnets", subnetIds,
                                                       "service.beta.kubernetes.io/aws-load-balancer-internal", "false"))
                                               .build())
                      .dependsOn(lbDependencies)
                      .provisioners(uponDestroyRun(NO_SPARK_LB_FINALIZERS))
                      .spec(ServiceSpec.builder()
                                       .selector(MASTER_LABELS)
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
            logger.info("Creating AWS Spark stack");
            final App app = createApp();
            new AwsSparkStack(app, AWS, new FilePropertyProvider());
            app.synth();
        } catch (RuntimeException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
    }

}
