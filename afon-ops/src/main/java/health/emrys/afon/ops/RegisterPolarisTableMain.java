package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import health.emrys.afon.polaris.config.RegisterTableConfig;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.cluster.ClusterUtils.isFailure;
import static java.lang.String.format;

/**
 * Run with args
 * <code>
 * -Dexec.args="GCP acceptancetests GcpAccessSpec260430301114 gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json  emryspolaris.click"
 * </code>
 */
public class RegisterPolarisTableMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterPolarisTableMain.class);

    private final AfonPolarisClient   client;
    private final RegisterTableConfig tableConfig;

    RegisterPolarisTableMain(AfonPolarisClient client, RegisterTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
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
        ClassicHttpResponse response = tableConfig.createTable(client);
        if (isFailure(response)) {
            String error = String.format(
                "HTTP code %d table %s.%s.%s",
                response.getCode(),
                tableConfig.getCatalog(),
                tableConfig.getNamespace(),
                tableConfig.getTable());
            logger.error(error);
            return List.of(error);
        }
        return List.of();
    }

    private List<String> createNamespace() throws IOException {
        ClassicHttpResponse response = tableConfig.createNamespace(client);
        if (isFailure(response)) {
            String error = String.format(
                "HTTP code %d when creating namespace %s.%s",
                response.getCode(),
                tableConfig.getCatalog(),
                tableConfig.getNamespace());
            logger.error(error);
            return List.of(error);
        }
        return List.of();
    }

    public static void main(String[] args) {
        AfonPolarisClientFactory clientFactory  = AfonPolarisClientFactory.clientFactory();
        if (args.length < 4) {
            throw new IllegalArgumentException(format("""
                    arguments are: 
                    catalog namespace table metadata_location
                    was provided with %d arguments: %s 
                    """, args.length, Arrays.stream(args).collect(Collectors.joining(" "))));
        }
        AfonCloud catalog = AfonProperties.AfonCloud.valueOf(args[0]);
        String namespace = args[1];
        String table = args[2];
        String metadata = args[3];

        RegisterTableConfig tableConfig = new RegisterTableConfig(catalog, namespace, table, metadata);
        Properties properties = new AfonPropertiesLoader().getProperties();
        if (args.length > 4) {
            properties.put(POLARIS_ENDPOINT.getName(), args[4]);
        }
        if (args.length > 5) {
            String userId = args[5];
            String secret = args[6];
            logger.info("Creating the table as {}/{}", userId, secret);
            properties.put(POLARIS_ID.getName(), userId);
            properties.put(POLARIS_SECRET.getName(), secret);
        }
        String kcHost = KC_HOSTNAME.getValueFrom(properties);
        logger.info("KC host: {}", kcHost);
        properties.put(KC_HOSTNAME.getName(), kcHost);
        AfonPolarisClient        client = clientFactory.getPolarisClient(properties);
        RegisterPolarisTableMain app    = new RegisterPolarisTableMain(client, tableConfig);
        List<String>             errors = app.createTableAndNamespace();
        if (!errors.isEmpty()) {
            throw new RuntimeException(errors.stream().collect(Collectors.joining("\n")));
        }
    }


}
