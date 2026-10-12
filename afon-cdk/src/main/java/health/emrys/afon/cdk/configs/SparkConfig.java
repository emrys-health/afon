package health.emrys.afon.cdk.configs;

import health.emrys.afon.cdk.CdkUtils;
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
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.List;

public class SparkConfig extends Construct {

    public final ClusterRoleBindingV1 operatorReady;

    public SparkConfig(@NonNull Construct scope,
                       @NonNull String id,
                       List<? extends ITerraformDependable> eksDependOn) {
        super(scope, id);
        // INSTALL SPARK OPERATOR (CRD CONTROLLER)
        Release sparkOperatorRelease = Release.Builder
            .create(this, "sparkOperator")
            .name("spark-kubernetes-operator")
            .chart(CdkUtils.vendoredChart(this, "sparkOperatorChart", "spark-kubernetes-operator-1.4.0.tgz"))
            .namespace("default")
            .atomic(true)
            .set(List.of(
                ReleaseSet
                    .builder()
                    .name("operatorConfiguration.log4j2\\.properties")
                    .value("rootLogger.level=DEBUG")
                    .build(),
                // The chart requests a whole CPU, which no node of a small cluster has left once the other pods have
                // theirs, so the pod would stay Pending until the release times out.  The limits are the chart's.
                ReleaseSet
                    .builder()
                    .name("operatorDeployment.operatorPod.operatorContainer.resources.requests.cpu")
                    .value("250m")
                    .build(),
                ReleaseSet
                    .builder()
                    .name("operatorDeployment.operatorPod.operatorContainer.resources.requests.memory")
                    .value("1Gi")
                    .build()))
            .dependsOn(eksDependOn) // Depend on EKS Auth
            .build();
        // The Helm chart's ClusterRole omits coordination.k8s.io/leases, which the operator
        // needs for leader election. A supplementary role + binding adds it without touching
        // the chart-managed ClusterRole (so it survives helm upgrade / tofu apply).
        ClusterRoleV1 leasesRole = ClusterRoleV1.Builder
            .create(this, "sparkOperatorLeasesRole")
            .metadata(ClusterRoleV1Metadata.builder()
                                           .name("spark-operator-leases")
                                           .build())
            .rule(List.of(ClusterRoleV1Rule
                              .builder()
                              .apiGroups(List.of("coordination.k8s.io"))
                              .resources(List.of("leases"))
                              .verbs(List.of("*"))
                              .build()))
            .dependsOn(List.of(sparkOperatorRelease))
            .build();
        operatorReady = ClusterRoleBindingV1.Builder
            .create(this, "sparkOperatorLeasesBinding")
            .metadata(ClusterRoleBindingV1Metadata
                          .builder()
                          .name("spark-operator-leases")
                          .build())
            .roleRef(ClusterRoleBindingV1RoleRef
                         .builder()
                         .apiGroup("rbac.authorization.k8s.io")
                         .kind("ClusterRole")
                         .name(leasesRole.getMetadata().getName())
                         .build())
            .subject(List.of(ClusterRoleBindingV1Subject
                                 .builder()
                                 .kind("ServiceAccount")
                                 .name("spark-operator")
                                 .namespace("default")
                                 .build()))
            .dependsOn(List.of(leasesRole))
            .build();
    }
}
