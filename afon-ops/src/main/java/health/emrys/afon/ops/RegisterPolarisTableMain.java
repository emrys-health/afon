package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisTableRegister;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.config.PolarisRestClientFactory.clientFactory;
import static java.lang.String.format;

/**
 * Run with args
 * <code>
 * "GCP acceptancetests GcpAccessSpec260430301114 gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json  emryspolaris.click"
 * </code>
 */
public class RegisterPolarisTableMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterPolarisTableMain.class);

    private final PolarisTableRegister tableRegister;

    public RegisterPolarisTableMain(PolarisTableRegister tableRegister) {
        this.tableRegister = tableRegister;
    }

    public List<String> createTableAndNamespace(String catalog, String namespace, String table, String metadata) {
        List<String> errors = new ArrayList<String>();
        try {
            errors.addAll(tableRegister.createNamespace(catalog, namespace));
            errors.addAll(tableRegister.createTable(catalog, namespace, table, metadata));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        return errors;
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println(format("""
            %s requires arguments: catalog namespace table metadata [<polaris-endpoint>] [<polaris-id>] [<polaris-secret>]
            """, RegisterPolarisTableMain.class.getSimpleName()));
            throw new IllegalArgumentException(format("""
                    required arguments are: catalog namespace table metadata
                    was provided with %d arguments: %s 
                    """, args.length, Arrays.stream(args).collect(Collectors.joining(" "))));
        }
        String catalog = AfonCloud.valueOf(args[0]).name();
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
            logger.info("Creating the table as {}:{}", polarisId, polarisSecret);
            properties.setValue(POLARIS_ID, polarisId);
            properties.setValue(POLARIS_SECRET, polarisSecret);
        }
        logger.info("KC host: {}", properties.getValue(KC_HOSTNAME));
        PolarisTableRegister register = new PolarisTableRegister(clientFactory().createClient(properties));
        RegisterPolarisTableMain app  = new RegisterPolarisTableMain(register);
        List<String>           errors = app.createTableAndNamespace(catalog, namespace, table, metadata);
        if (!errors.isEmpty()) {
            throw new RuntimeException(errors.stream().collect(Collectors.joining("\n")));
        }
    }


}
