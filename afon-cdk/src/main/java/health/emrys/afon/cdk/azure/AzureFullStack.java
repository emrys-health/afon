package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import health.emrys.afon.cdk.*;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.config.AfonCloud.AZURE;

public class AzureFullStack extends AzurePolarisStack {

    public AzureFullStack(Construct scope, StackId stackId, AfonPropertyProvider properties) {
        super(scope, stackId, properties);

        // The Azure CNI (Cilium) data plane is not necessarily ready the moment the
        // azurerm_kubernetes_cluster resource reports "created". Gating the Spark
        // operator on nginxRelease (which has already scheduled pods successfully)
        // proves the cilium-agent is serving requests, avoiding the
        // "Cilium API client timeout exceeded" pod-sandbox failure.
        List<Release> dependencies        = List.of(nginxRelease);
        SparkConfig   sparkOperatorConfig = new SparkConfig(this, "sparkop", dependencies);

        // The SparkApplication driver runs under this service account
        // (spark.kubernetes.authenticate.driver.serviceAccountName).
        ServiceAccount.Builder
            .create(this, "sparkApplicationSa")
            .metadata(ServiceAccountMetadata.builder()
                                            .name(SPARK_SERVICE_ACCOUNT_NAME)
                                            .namespace("default")
                                            .build())
            .build();

        // Installing the operator alone leaves it idle — it only launches a master/workers
        // and a driver/executors in response to the SparkCluster/SparkApplication CRs.
        List<ITerraformDependable> sparkDependsOn = new ArrayList<>(cloudProvider.getDependables());
        sparkDependsOn.add(sparkOperatorConfig.operatorReady);

        // deploy the Spark workers and driver pointing at Azure Polaris
        SparkWorkloads sparkDeployer = new SparkWorkloads();
        sparkDeployer.deploy(this, AZURE, sparkDependsOn);

        JupyterHubConfig jupyter = new AzureJupyterHubConfig(this, properties);
        jupyter.deployJupyterHub(cloudProvider.getDependables());
    }

    public static void main(String[] args) {
        final App app = createApp();
        new AzureFullStack(app, StackId.of(AzureFullStack.class), new FilePropertyProvider());
        app.synth();
    }

}
