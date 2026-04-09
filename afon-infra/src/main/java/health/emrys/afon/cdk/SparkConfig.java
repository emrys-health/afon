package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_binding_v1.ClusterRoleBindingV1;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_binding_v1.ClusterRoleBindingV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_binding_v1.ClusterRoleBindingV1RoleRef;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_binding_v1.ClusterRoleBindingV1Subject;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_v1.ClusterRoleV1;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_v1.ClusterRoleV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.cluster_role_v1.ClusterRoleV1Rule;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.List;

public class SparkConfig extends Construct {

    public final ClusterRoleBindingV1 operatorReady;

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
                                                      .set(List.of(
                                                              ReleaseSet.builder()
                                                                        .name("operatorConfiguration.log4j2\\.properties")
                                                                        .value("rootLogger.level=DEBUG")
                                                                        .build()))
                                                      .dependsOn(eksDependOn) // Depend on EKS Auth
                                                      .build();
        // The Helm chart's ClusterRole omits coordination.k8s.io/leases, which the operator
        // needs for leader election. A supplementary role + binding adds it without touching
        // the chart-managed ClusterRole (so it survives helm upgrade / tofu apply).
        ClusterRoleV1 leasesRole = ClusterRoleV1.Builder.create(this, "sparkOperatorLeasesRole")
                .metadata(ClusterRoleV1Metadata.builder()
                        .name("spark-operator-leases")
                        .build())
                .rule(List.of(ClusterRoleV1Rule.builder()
                        .apiGroups(List.of("coordination.k8s.io"))
                        .resources(List.of("leases"))
                        .verbs(List.of("*"))
                        .build()))
                .dependsOn(List.of(sparkOperatorRelease))
                .build();
        operatorReady = ClusterRoleBindingV1.Builder.create(this, "sparkOperatorLeasesBinding")
                .metadata(ClusterRoleBindingV1Metadata.builder()
                        .name("spark-operator-leases")
                        .build())
                .roleRef(ClusterRoleBindingV1RoleRef.builder()
                        .apiGroup("rbac.authorization.k8s.io")
                        .kind("ClusterRole")
                        .name(leasesRole.getMetadata().getName())
                        .build())
                .subject(List.of(ClusterRoleBindingV1Subject.builder()
                        .kind("ServiceAccount")
                        .name("spark-operator")
                        .namespace("default")
                        .build()))
                .dependsOn(List.of(leasesRole))
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
