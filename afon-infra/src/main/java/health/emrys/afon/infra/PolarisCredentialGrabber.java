package health.emrys.afon.infra;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.EksInfoClient;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.util.Properties;

import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static health.emrys.afon.infra.Defaults.POLARIS_POD_NAME;

public class PolarisCredentialGrabber {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisCredentialGrabber.class);

    public static void main(String[] args) {
        Properties properties  = new AfonPropertiesLoader().getProperties();
        var        eksClient   = new EksInfoClient(CLUSTER_NAME, properties);
        var        app         = new PolarisCredentialGrabber();
        try {
            System.out.println(app.getCredentials(eksClient.getLogsOf(POLARIS_POD_NAME)));
        } catch (ApiException e) {
            e.printStackTrace();
        }
    }

    private final LogParser logParser;

    public PolarisCredentialGrabber() {
        logParser = new LogParser();
    }

    public String getCredentials(String logs) {
        return logParser.searchForTextAfter("realm: POLARIS root principal credentials: ", logs);
    }



}
