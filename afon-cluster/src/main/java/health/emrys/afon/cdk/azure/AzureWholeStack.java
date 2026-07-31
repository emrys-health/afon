package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import health.emrys.afon.cdk.*;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static health.emrys.afon.cluster.Defaults.SPARK_SERVICE_ACCOUNT_NAME;

import static health.emrys.afon.cdk.JupyterMfaConfig.PROXY_PORT;
import static health.emrys.afon.cdk.JupyterMfaConfig.PROXY_SVC_NAME;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.cluster.Defaults.JUPYTER_INGRESS;
import static health.emrys.afon.config.AfonProperties.AfonCloud.AZURE;

public class AzureWholeStack extends AzureKubernetesPolaris {

    private final String TLS_SECRET_NAME = "jupyter-secret";

    public AzureWholeStack(Construct scope, String id, Properties properties) {
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
        SparkWorkloads sparkDeployer = new SparkWorkloads(AZURE_DOMAIN_NAME.getValueFrom(properties));
        sparkDeployer.deploy(this, AZURE, sparkDependsOn);

        String jupyterDomainName = AZURE_JUPYTER_DOMAIN_NAME.getValueFrom(properties);
        var util = new TFUtils(
            this, jupyterDomainName, TLS_SECRET_NAME,
            PROXY_PORT, PROXY_SVC_NAME, JUPYTER_INGRESS);
        JupyterHubConfig jupyter = new JupyterHubConfig(this, properties) {

            @Override
            protected void configureHttpsAndDNS() {
                IngressV1 ingress = util.createIngress(
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
                    AzureWholeStack.this,
                    proxyDeps,
                    JupyterHubConfig.MFA_UPSTREAMS,
                    jupyterDomainName,
                    AZURE_OAUTH2_CLIENT_ID.getValueFrom(properties),
                    AZURE_OAUTH2_CLIENT_SECRET.getValueFrom(properties),
                    AZURE_OAUTH2_COOKIE_SECRET.getValueFrom(properties)
                    );
            }

            @Override
            protected String jupyterDomain() {
                return jupyterDomainName;
            }

            @Override
            protected String oauthClientId() {
                return AZURE_OAUTH2_CLIENT_ID.getValueFrom(properties);
            }

            @Override
            protected String oauthClientSecret() {
                return AZURE_OAUTH2_CLIENT_SECRET.getValueFrom(properties);
            }
        };
        jupyter.deployJupyterHub(cloudProvider.getDependables());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureWholeStack(app, AZURE.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }

}
