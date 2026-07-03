package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.platform.SparkEngineDeployer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.*;

/**
 * This was initially run as a step after its superclass so it could be configured with domain names etc.
 * But now we're more production like (static domain names; persisted Polaris metadata etc)
 * this could be feasibly moved into its parent class.
 */
public class SparkClusterDeployment extends AwsWholeStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkClusterDeployment.class);

    public SparkClusterDeployment(@NotNull Construct scope,
                                  @NotNull SupportedCloudsEnum id,
                                  Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> eksDependOn = new ArrayList<>(cloudProvider.getDependables());
        eksDependOn.add(sparkOperatorConfig.operatorReady);

        new SparkEngineDeployer().deployWorkloads(this, sparkOperatorConfig);

        exposeSparkMaster(eksDependOn);
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
