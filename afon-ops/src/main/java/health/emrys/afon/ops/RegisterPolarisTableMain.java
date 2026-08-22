package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import health.emrys.afon.polaris.config.RegisterTableConfig;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static health.emrys.afon.cluster.ClusterUtils.isFailure;
import static health.emrys.afon.config.AfonProperty.*;
import static java.lang.String.format;

/**
 * Run with args
 * <code>
 * -Dexec.args="GCP acceptancetests GcpAccessSpec260430301114 gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json  emryspolaris.click"
 * </code>
 */
public class RegisterPolarisTableMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterPolarisTableMain.class);

    private final PolarisRestClient client;
    private final RegisterTableConfig tableConfig;

    RegisterPolarisTableMain(PolarisRestClient client, RegisterTableConfig tableConfig) {
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
        if (args.length < 4) {
            throw new IllegalArgumentException(format("""
                    required arguments are: 
                    catalog namespace table metadata_location
                    was provided with %d arguments: %s 
                    """, args.length, Arrays.stream(args).collect(Collectors.joining(" "))));
        }
        AfonCloud catalog = AfonCloud.valueOf(args[0]);
        String namespace = args[1];
        String table = args[2];
        String metadata = args[3];

        var properties = new FilePropertyProvider();
        if (args.length > 4) {
            properties.setValue(POLARIS_ENDPOINT, args[4]);
        }
        if (args.length > 5) {
            String polarisId = args[5];
            String polarisSecret = args[6];
            logger.info("Creating the table as {}/{}", polarisId, polarisSecret);
            properties.setValue(POLARIS_ID, polarisId);
            properties.setValue(POLARIS_SECRET, polarisSecret);
        }
        String kcHost = properties.getValue(KC_HOSTNAME);
        logger.info("KC host: {}", kcHost);
        PolarisRestClient client = PolarisRestClientFactory.clientFactory().createClient(properties);
        RegisterTableConfig tableConfig = new RegisterTableConfig(catalog, namespace, table, metadata);
        RegisterPolarisTableMain app    = new RegisterPolarisTableMain(client, tableConfig);
        List<String>             errors = app.createTableAndNamespace();
        if (!errors.isEmpty()) {
            throw new RuntimeException(errors.stream().collect(Collectors.joining("\n")));
        }
    }


}
