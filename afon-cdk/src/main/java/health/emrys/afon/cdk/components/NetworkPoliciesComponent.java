package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.StackComponent;

import health.emrys.afon.cdk.configs.NetworkConfig;

import static health.emrys.afon.cdk.CdkHelper.SPARK_CONNECTOR_NAME;

/**
 * The network policies locking down the notebook pods and the Spark master and driver, which need the cluster's CNI to
 * enforce them.
 */
public class NetworkPoliciesComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        new NetworkConfig(context.stack(), context.properties(), context.platform().ingressPodLabels())
            .networkPolicies(SPARK_CONNECTOR_NAME, context.platform().getDependables());
    }
}
