package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.List;

public class SparkConfig extends Construct {

    public SparkConfig(@NotNull Construct scope,
                       @NotNull String id,
                       List<? extends ITerraformDependable> eksDependOn) {
        super(scope, id);
        // INSTALL SPARK OPERATOR (CRD CONTROLLER)
        Release sparkOperatorRelease = Release.Builder.create(this, "sparkOperator")
                                                      .name("spark-kubernetes-operator")
                                                      .chart("spark-kubernetes-operator")
                                                      .version("1.4.0")
                                                      .repository("https://apache.github.io/spark-kubernetes-operator")
                                                      .namespace("default")
                                                      .atomic(true)
                                                      .dependsOn(eksDependOn) // Depend on EKS Auth
                                                      .build();
        /*
        This gives:
        Error running command 'helm status spark-operator --namespace spark-operator --wait': exit status 1. Output: Error: unknown flag: --wait
        So ignoring it for now since it's not needed until Spark deployment and if we call this at the same time as
        EKS setup, it's not an issue.
         */

        // Block until the Spark Operator Helm Release is fully installed and ready
//        Resource sparkOperatorReadyWaiter = Resource.Builder.create(this, "sparkOperatorReadyWaiter")
//                                                            .dependsOn(List.of(sparkOperatorRelease)) // Must wait for the release object to be created
//                                                            .provisioners(List.of(
//                                                                    LocalExecProvisioner.builder()
//                                                                                        .type("local-exec")
//                                                                                        .when("create")
//                                                                                        // The --wait flag blocks until all resources are in a ready state.
//                                                                                        .command("helm status spark-operator --namespace spark-operator --wait")
//                                                                                        .build()
//                                                                                 ))
//                                                            .build();
    }
}
