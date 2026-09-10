package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisTableRegister;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.config.PolarisRestClientFactory.clientFactory;

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

    public void createNamespaceAndTable(String catalog, String namespace, String table, String metadata) {
        List<HttpResponse> allResponses = new ArrayList<>();
        try {
            allResponses.addAll(tableRegister.createNamespace(catalog, namespace));
            allResponses.addAll(tableRegister.createTable(catalog, namespace, table, metadata));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        List<String> failures = PolarisRestClient.failuresOf(allResponses);
        if ( !failures.isEmpty() ){
            throw new RuntimeException("Failed to create namespace and table: " + String.join(", ", failures));
        }
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            String usage = String.format("""
                    
                    Invalid arguments: '%s'
                    usage:  %s supports the following args options:
                            cloud catalog namespace table metadata [polaris-id] [polaris-secret]
                    """, String.join(" ", args), RegisterPolarisTableMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }
        var properties = new FilePropertyProvider();
        String cloud = args[0];
        String catalog = new AfonCatalogs(properties).get(args[1]).toString();
        String namespace = args[2];
        String table = args[3];
        String metadata = args[4];
        String polarisDomain = getPolarisDomain(cloud, properties);
        properties.setValue(POLARIS_ENDPOINT, polarisDomain);
        if (args.length > 5) {
            String polarisId = args[5];
            String polarisSecret = args[6];
            logger.info("Creating the table as {}:{}", polarisId, polarisSecret);
            properties.setValue(POLARIS_ID, polarisId);
            properties.setValue(POLARIS_SECRET, polarisSecret);
        }
        logger.info("OIDC domain: {}", properties.getValue(OIDC_DOMAIN_NAME));
        PolarisTableRegister register = new PolarisTableRegister(clientFactory().createClient(properties));
        RegisterPolarisTableMain app  = new RegisterPolarisTableMain(register);
        app.createNamespaceAndTable(catalog, namespace, table, metadata);
    }

    private static String getPolarisDomain(String cloud, FilePropertyProvider properties) {
        String polarisDomain;
        switch (cloud) {
            case "aws":
                polarisDomain = properties.getValue(AWS_POLARIS_DOMAIN_NAME);
                break;
            case "azure":
                polarisDomain = properties.getValue(AZURE_POLARIS_DOMAIN_NAME);
                break;
            case "gcp":
                polarisDomain = properties.getValue(GCP_POLARIS_DOMAIN_NAME);
                break;
            default:
                throw new IllegalArgumentException("Unknown cloud: " + cloud);
        }
        return polarisDomain;
    }


}
