package health.emrys.afon.cdk.component;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.SparkWorkloads;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spark cluster and the Spark Connect application, which the {@link SparkOperatorComponent} launches, exposed
 * outside the cluster on the platforms that do.
 */
public class SparkComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        dependsOn.add(context.require(SparkOperatorComponent.class).operatorReady());
        new SparkWorkloads().deploy(context.stack(), platform.cloud(), dependsOn);
        platform.exposeSparkMaster(dependsOn);
    }
}
