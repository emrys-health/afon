package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.StackComponent;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.configs.SparkWorkloads;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spark cluster and the Spark Connect application, which the {@link SparkOperatorComponent} launches.
 */
public class SparkComponent implements StackComponent {

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(SparkOperatorComponent.class);
    }


    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        dependsOn.add(context.require(SparkOperatorComponent.class).operatorReady());
        new SparkWorkloads().deploy(context.stack(), platform.cloud(), dependsOn);
    }
}
