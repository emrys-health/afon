package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.KeyCloakPolarisClient;
import health.emrys.security.keycloak.KeyCloakConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.KC_HOSTNAME;
import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.security.keycloak.KeyCloakConfig.tokenEndpoint;

/**
 * Run with something like:
 * <code>
 mvn compile -Dexec.mainClass=health.emrys.afon.core.configuration.LocalPolarisRegisterMain -Dexec.args="GCP acceptancetests GcpAccessSpec260430301114 gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json  emryspolaris.click" exec:java
</code>
 */
public class LocalPolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(LocalPolarisRegisterMain.class);

    public static void main(String[] args) {
        RegisterTableConfig conf           = new RegisterTableConfig(args);
        AfonPolarisClient   client         = null;
        Properties          afonProperties = new AfonPropertiesLoader().getProperties();
        if (args.length == 5) {
            afonProperties.put(POLARIS_ENDPOINT.getPropertyName(), args[4]);
        }
        String kcHost = KC_HOSTNAME.getValueFrom(afonProperties);
        logger.info("KC host: {}", kcHost);
        client = new KeyCloakPolarisClient(afonProperties, kcHost);
        try {
            conf.createNamespace(client);
            conf.createTable(client);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

}
