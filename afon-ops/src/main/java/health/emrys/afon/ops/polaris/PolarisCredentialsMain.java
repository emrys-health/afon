package health.emrys.afon.ops.polaris;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.config.PolarisCredentials;

public class PolarisCredentialsMain {

    public void accessToken(PolarisCredentials credentials) {
        System.out.printf("Access token for polarisId '%s' on cloud '%s': %s",
                credentials.getPolarisId(), credentials.getCloud(), credentials.getAccessToken());
    }

    public static void main(String[] args) {
        var app = new PolarisCredentialsMain();
        var properties = new OpsPropertyProvider();
        if (args.length == 0) {
            System.out.printf("%s requires argument: cloud", PolarisCredentialsMain.class.getSimpleName());
            return;
        }
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        app.accessToken(new PolarisCredentials(cloud, properties));
    }

}