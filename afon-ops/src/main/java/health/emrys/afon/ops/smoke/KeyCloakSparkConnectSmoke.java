package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.spark.connect.KeyCloakSparkConnect;
import health.emrys.afon.spark.connect.KeyCloakSparkConnectUsingOIDC;

import java.io.IOException;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * First run:
 * kubectl port-forward pod/spark-connector-0-driver 15002:15002
 *
 * You may need to register some data in Polaris to run this.
 * If so, try running something like:
 * health.emrys.afon.ops.RegisterPolarisTableMain with args "AWS acceptancetests AwsAccessSpec s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click"
 */
public class KeyCloakSparkConnectSmoke {

    public void smokeTest(KeyCloakSparkConnect connect) {
        connect.talkToSparkConnect();
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.printf("%s requires argument: polaris-endpoint", KeyCloakSparkConnectSmoke.class.getSimpleName());
            return;
        }
        var app = new KeyCloakSparkConnectSmoke();
        var properties = new FilePropertyProvider();
        String polarisEndpoint = args[0];
        properties.setValue(POLARIS_ENDPOINT, polarisEndpoint);
        try {
            app.smokeTest(new KeyCloakSparkConnect(properties));
            app.smokeTest(new KeyCloakSparkConnectUsingOIDC(properties));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

