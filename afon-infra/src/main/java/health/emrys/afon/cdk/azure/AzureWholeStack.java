package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import health.emrys.afon.cdk.SparkConfig;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.platform.JupyterWorkbenchDeployer;
import health.emrys.afon.platform.SparkEngineDeployer;
import software.constructs.Construct;

import java.util.Properties;

public class AzureWholeStack extends AzureKubernetesPolaris {

    public AzureWholeStack(Construct scope, String id, Properties properties) {
        super(scope, id, properties);

        // Operator gated on workloadReadiness() (the nginx release — proof the
        // Cilium data plane is serving); workloads additionally gate on the
        // operator-ready signal. See ProvisionedCloud#workloadReadiness.
        SparkEngineDeployer spark    = new SparkEngineDeployer();
        SparkConfig         operator = spark.installOperator(this);
        spark.deployWorkloads(this, operator);

        new JupyterWorkbenchDeployer().deployUsingProperties(this);
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureWholeStack(app, ID.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }

}
