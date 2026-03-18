package health.emrys.afon.core.configuration;

import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.slf4j.Logger;

import static java.lang.String.format;

public class PolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisRegisterMain.class);

    public static void main(String[] args) {
        try (ClusterState state = new ClusterState()) {
            RegisterTableConfig conf        = new RegisterTableConfig(args);
            String              endpoint    = format("https://%s", state.getPolarisHost());
            String              accessToken = state.getCredentials();
            String[]            creds       = accessToken.split(":");
            AfonPolarisClient   client      = new AfonPolarisClient(endpoint,
                                                             creds[0],
                                                             creds[1]);

            conf.createNamespace(client);
            conf.createTable(client);
            logger.info("Finished without error. Shutting down resources...");
        } catch (Exception e) {
            logger.error("Could not register table", e);
        }
    }
}
