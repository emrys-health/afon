package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.slf4j.Logger;

import java.io.IOException;

public class LocalPolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(LocalPolarisRegisterMain.class);

    public static void main(String[] args) {
        RegisterTableConfig conf   = new RegisterTableConfig(args);
        AfonPolarisClient   client = new AfonPolarisClient(new AfonPropertiesLoader().getProperties());
        try {
            conf.createNamespace(client);
            conf.createTable(client);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

}
