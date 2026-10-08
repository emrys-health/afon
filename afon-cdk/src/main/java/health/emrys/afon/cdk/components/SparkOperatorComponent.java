package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.StackComponent;

import com.hashicorp.cdktf.providers.kubernetes.cluster_role_binding_v1.ClusterRoleBindingV1;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import health.emrys.afon.cdk.configs.SparkConfig;

import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;

/**
 * The Spark operator, which only launches a master, workers and a driver in response to the SparkCluster and
 * SparkApplication resources of {@link SparkComponent}, and the service account the driver runs under.
 */
public class SparkOperatorComponent implements StackComponent {

    private SparkConfig sparkOperatorConfig;

    @Override
    public void deploy(StackContext context) {
        // The SparkApplication driver runs under this service account
        // (spark.kubernetes.authenticate.driver.serviceAccountName).
        ServiceAccount.Builder
            .create(context.stack(), "sparkApplicationSa")
            .metadata(ServiceAccountMetadata.builder()
                                            .name(SPARK_SERVICE_ACCOUNT_NAME)
                                            .namespace("default")
                                            .build())
            .build();
        sparkOperatorConfig = new SparkConfig(context.stack(), "sparkop", context.platform().sparkOperatorDependencies());
    }

    /**
     * @return the signal that the operator is ready, ie that its CRDs exist
     */
    public ClusterRoleBindingV1 operatorReady() {
        return sparkOperatorConfig.operatorReady;
    }
}
