package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import health.emrys.afon.cdk.*;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static health.emrys.afon.cdk.CdkHelper.PROXY_PORT;
import static health.emrys.afon.cdk.CdkHelper.PROXY_SERVICE_NAME;
import static health.emrys.afon.cdk.CdkHelper.JUPYTER_INGRESS_NAME;
import static health.emrys.afon.cdk.CdkHelper.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.*;

public class AzureFullStack extends AzurePolarisStack {

    private final String TLS_SECRET_NAME = "jupyter-secret";

    public AzureFullStack(Construct scope, AfonCloud id, AfonPropertyProvider properties) {
        super(scope, id, properties);

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

        String jupyterDomainName = properties.getValue(AZURE_JUPYTER_DOMAIN_NAME);
        var helper = new CdkHelper(
            this, jupyterDomainName, TLS_SECRET_NAME,
            PROXY_PORT, PROXY_SERVICE_NAME, JUPYTER_INGRESS_NAME);
        JupyterHubConfig jupyter = new JupyterHubConfig(this, properties) {

            @Override
            protected void configureHttpsAndDNS() {
                IngressV1 ingress = helper.createIngress(
                    List.of(
                        nginxRelease, certManager),
                    Optional.empty());
                configureDns(
                    ingress,
                    jupyterDomainName);
            }

            @Override
            protected JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps) {
                return new JupyterMfaConfig(
                    AzureFullStack.this,
                    proxyDeps,
                    properties,
                    JupyterHubConfig.MFA_UPSTREAMS,
                    jupyterDomainName,
                    properties.getValue(AZURE_OAUTH2_CLIENT_ID),
                    properties.getValue(AZURE_OAUTH2_CLIENT_SECRET),
                    properties.getValue(AZURE_OAUTH2_COOKIE_SECRET)
                    );
            }

            @Override
            protected String jupyterDomain() {
                return jupyterDomainName;
            }

            @Override
            protected String oauthClientId() {
                return properties.getValue(AZURE_OAUTH2_CLIENT_ID);
            }

            @Override
            protected String oauthClientSecret() {
                return properties.getValue(AZURE_OAUTH2_CLIENT_SECRET);
            }
        };
        jupyter.deployJupyterHub(cloudProvider.getDependables());
    }

    public static void main(String[] args) {
        final App app = createApp();
        new AzureFullStack(app, AZURE, new FilePropertyProvider());
        app.synth();
    }

}
