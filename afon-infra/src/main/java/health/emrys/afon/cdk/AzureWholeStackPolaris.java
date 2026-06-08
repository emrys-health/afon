package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.Properties;

public class AzureWholeStackPolaris extends AzureKubernetesPolaris {
    public AzureWholeStackPolaris(Construct scope, String id, Properties properties) {
        super(scope, id, properties);
//        SparkConfig sparkOperatorConfig = new SparkConfig(this, "sparkop", cloudProvider.getDependables());
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
