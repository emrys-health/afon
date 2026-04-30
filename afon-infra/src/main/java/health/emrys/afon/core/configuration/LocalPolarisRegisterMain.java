package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

public class LocalPolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(LocalPolarisRegisterMain.class);

    public static void main(String[] args) {
        RegisterTableConfig conf           = new RegisterTableConfig(args);
        AfonPolarisClient   client         = null;
        Properties          afonProperties = new AfonPropertiesLoader().getProperties();
        if (args.length == 5) {
            client = new AfonPolarisClient(args[4], afonProperties);
        } else {
            client = new AfonPolarisClient(afonProperties);
        }
        try {
            conf.createNamespace(client);
            conf.createTable(client);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

}
