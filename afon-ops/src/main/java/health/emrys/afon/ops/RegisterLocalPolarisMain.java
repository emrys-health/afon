package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.cluster.ClusterUtils.isFailure;

/**
 * Run with args
 * <code>
 * -Dexec.args="GCP acceptancetests GcpAccessSpec260430301114 gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json  emryspolaris.click"
 * </code>
 */
public class RegisterLocalPolarisMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterLocalPolarisMain.class);

    private final AfonPolarisClient   client;
    private final RegisterPolarisMain.RegisterTableConfig conf;

    RegisterLocalPolarisMain(AfonPolarisClient client, RegisterPolarisMain.RegisterTableConfig conf) {
        this.client = client;
        this.conf   = conf;
    }

    private List<String> createTableAndNamespace() {
        List<String> errors = new ArrayList<String>();
        try {
            errors.addAll(createNamespace());
            errors.addAll(createTable());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        return errors;
    }

    private List<String> createTable() throws IOException {
        ClassicHttpResponse response = conf.createTable(client);
        if (isFailure(response)) {
            String error = String.format(
                "HTTP code %d table %s.%s.%s",
                response.getCode(),
                conf.getCatalog(),
                conf.getNamespace(),
                conf.getTable());
            logger.error(error);
            return List.of(error);
        }
        return List.of();
    }

    private List<String> createNamespace() throws IOException {
        ClassicHttpResponse response = conf.createNamespace(client);
        if (isFailure(response)) {
            String error = String.format(
                "HTTP code %d when creating namespace %s.%s",
                response.getCode(),
                conf.getCatalog(),
                conf.getNamespace());
            logger.error(error);
            return List.of(error);
        }
        return List.of();
    }

    public static void main(String[] args) {
        AfonPolarisClientFactory clientFactory  = AfonPolarisClientFactory.clientFactory();
        RegisterPolarisMain.RegisterTableConfig conf           = new RegisterPolarisMain.RegisterTableConfig(args);
        Properties               afonProperties = new AfonPropertiesLoader().getProperties();
        if (args.length > 4) {
            afonProperties.put(POLARIS_ENDPOINT.getName(), args[4]);
        }
        if (args.length > 5) {
            String userId = args[5];
            String secret = args[6];
            logger.info("Creating the table as {}/{}", userId, secret);
            afonProperties.put(POLARIS_ID.getName(), userId);
            afonProperties.put(POLARIS_SECRET.getName(), secret);
        }
        String kcHost = KC_HOSTNAME.getValueFrom(afonProperties);
        logger.info("KC host: {}", kcHost);
        afonProperties.put(KC_HOSTNAME.getName(), kcHost);
        AfonPolarisClient        client = clientFactory.getPolarisClient(afonProperties);
        RegisterLocalPolarisMain app    = new RegisterLocalPolarisMain(client, conf);
        List<String>             errors = app.createTableAndNamespace();
        if (!errors.isEmpty()) {
            throw new RuntimeException(errors.stream().collect(Collectors.joining("\n")));
        }
    }


}
