package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.List;
import java.util.Properties;

public class AzureWholeStackPolaris extends AzureKubernetesPolaris {
    public AzureWholeStackPolaris(Construct scope, String id, Properties properties) {
        super(scope, id, properties);
        // The Azure CNI (Cilium) data plane is not necessarily ready the moment the
        // azurerm_kubernetes_cluster resource reports "created". Gating the Spark
        // operator on nginxRelease (which has already scheduled pods successfully)
        // proves the cilium-agent is serving requests, avoiding the
        // "Cilium API client timeout exceeded" pod-sandbox failure.
        new SparkConfig(this, "sparkop", List.of(nginxRelease));
//        JupyterConfig jupyter = new JupyterConfig(this, properties) {
//
//            @Override
//            protected void configureHttpsAndDNS() {
//
//            }
//
//            @Override
//            protected String getJupyterDomain() {
//                return "";
//            }
//        };
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureWholeStackPolaris(app, ID.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }

}
