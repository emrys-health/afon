package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static health.emrys.afon.cdk.JupyterMfaConfig.PROXY_PORT;
import static health.emrys.afon.cdk.JupyterMfaConfig.PROXY_SVC_NAME;
import static health.emrys.afon.config.AfonProperties.AZURE_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.infra.Defaults.JUPYTER_INGRESS;

public class AzureWholeStack extends AzureKubernetesPolaris {

    private final String TLS_SECRET_NAME = "jupyter-secret";

    public AzureWholeStack(Construct scope, String id, Properties properties) {
        super(scope, id, properties);

        // The Azure CNI (Cilium) data plane is not necessarily ready the moment the
        // azurerm_kubernetes_cluster resource reports "created". Gating the Spark
        // operator on nginxRelease (which has already scheduled pods successfully)
        // proves the cilium-agent is serving requests, avoiding the
        // "Cilium API client timeout exceeded" pod-sandbox failure.
//        List<Release> dependencies = List.of(nginxRelease);
//        new SparkConfig(this, "sparkop", dependencies);

        String jupyterDomainName = AZURE_JUPYTER_DOMAIN_NAME.getValueFrom(properties);
        var util = new TFUtils(
            this, jupyterDomainName, TLS_SECRET_NAME,
            PROXY_PORT, PROXY_SVC_NAME, JUPYTER_INGRESS);
        JupyterConfig jupyter = new JupyterConfig(this, properties) {

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
            protected String getJupyterDomain() {
                return jupyterDomainName;
            }
        };
        jupyter.deployJupyter(cloudProvider.getDependables());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureWholeStack(app, ID.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }

}
