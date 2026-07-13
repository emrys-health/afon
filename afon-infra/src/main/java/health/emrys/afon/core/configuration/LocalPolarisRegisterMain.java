package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.AfonPolarisClientFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.InfraUtils.isFailure;

/**
 * Run with something like:
 * <code>
 * mvn compile -Dexec.mainClass=health.emrys.afon.core.configuration.LocalPolarisRegisterMain -Dexec.args="GCP acceptancetests GcpAccessSpec260430301114 gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json  emryspolaris.click" exec:java
 * </code>
 */
public class LocalPolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(LocalPolarisRegisterMain.class);

    public static void main(String[] args) {
        AfonPolarisClientFactory clientFactory  = AfonPolarisClientFactory.clientFactory();
        RegisterTableConfig      conf           = new RegisterTableConfig(args);
        AfonPolarisClient        client         = null;
        Properties               afonProperties = new AfonPropertiesLoader().getProperties();
        if (args.length > 4) {
            afonProperties.put(POLARIS_ENDPOINT.getPropertyName(), args[4]);
        }
        if (args.length > 5) {
            String userId = args[5];
            String secret = args[6];
            logger.info("Creating the table as {}/{}", userId, secret);
            afonProperties.put(POLARIS_ID.getPropertyName(), userId);
            afonProperties.put(POLARIS_SECRET.getPropertyName(), secret);
        }
        String kcHost = KC_HOSTNAME.getValueFrom(afonProperties);
        logger.info("KC host: {}", kcHost);
        afonProperties.put(KC_HOSTNAME.getPropertyName(), kcHost);
        client = clientFactory.getPolarisClient(afonProperties);
        try {
            if (isFailure(conf.createNamespace(client))) {
                logger.error("Cannot create namespace {}.{}", conf.getCatalog(), conf.getNamespace());
            }
            if (isFailure(conf.createTable(client))) {
                logger.error("Cannot create table {}.{}.{}", conf.getCatalog(), conf.getNamespace(), conf.getTable());
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

}
