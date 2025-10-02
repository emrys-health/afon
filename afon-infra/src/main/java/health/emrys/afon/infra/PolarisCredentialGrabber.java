package health.emrys.afon.infra;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.EksInfoClient;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PolarisCredentialGrabber {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisCredentialGrabber.class);

    public static void main(String[] args) {
        String     clusterName = "spark-cluster";
        Properties properties  = new AfonPropertiesLoader().getProperties();
        var        eksClient   = new EksInfoClient(clusterName, properties);
        var        app         = new PolarisCredentialGrabber();
        try {
            System.out.println(app.getCredentials(eksClient.getLogsOf("polaris")));
        } catch (ApiException e) {
            e.printStackTrace();
        }
    }

    public String getCredentials(String logs) {
        logger.info("Logs are {} characters long", logs.length());
        Pattern pattern = Pattern.compile("realm: POLARIS root principal credentials: (.*)");
        Matcher matcher = pattern.matcher(logs);
        matcher.find();
        return matcher.group(1);
    }

}
