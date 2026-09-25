package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.config.PolarisCredentials;

public class ShowAccessTokenMain {

    public void accessToken(PolarisCredentials credentials) {
        System.out.printf("Access token for polarisId '%s' on cloud '%s': %s",
                credentials.getPolarisId(), credentials.getCloud(), credentials.getAccessToken());
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            String message = String.format("""
                    %s requires argument: cloud
                    Uses the POLARIS_ID and POLARIS_SECRET properties to authenticate via PolarisRestClient.
                    """, ShowAccessTokenMain.class.getSimpleName());
            System.out.println(message);
            return;
        }
        var app = new ShowAccessTokenMain();
        var properties = new OpsPropertyProvider();
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        app.accessToken(new PolarisCredentials(cloud, properties));
    }

}