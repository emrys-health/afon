package health.emrys.afon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.polaris.config.PolarisConfigurer.*;
import static org.slf4j.LoggerFactory.getLogger;

public class PurgeAndPopulatePolarisMain {

    private static final Logger logger = getLogger(PurgeAndPopulatePolarisMain.class);

    private final PolarisRestClient client;
    private final String            accessToken;
    private final String            domainName;
    private final ObjectMapper      mapper = new ObjectMapper();
    private final Purger            purger;

    public PurgeAndPopulatePolarisMain(String domainName) {
        PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
        this.domainName  = domainName;
        this.client      = clientFactory.createClient(domainName, new FilePropertyProvider().getProperties());
        this.accessToken = client.getAccessToken();
        logger.debug("For {}/{}:\nPOLARIS_TOKEN={}", client.getClientId(), client.getClientSecret(), accessToken);
        purger = new Purger(client, accessToken, domainName);
    }


    public void createAllCatalogs() throws IncorrectConfigException, IOException, ParseException {
        purgeRecursivelyForAllCatalogs();
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        config.initializePolaris();
    }

    private void purgeRecursivelyForAllCatalogs() throws IOException, ParseException {
        for (AfonCloud warehouse : List.of(AZURE, AWS, GCP)) {
            purger.purge(warehouse.toString());
            client.delete(catalogsURI(warehouse.toString()), accessToken);
            purgeRolesOf(warehouse);
        }
    }

    private void purgeRolesOf(AfonCloud warehouse) throws IOException, ParseException {
        String uri = catalogRolesURI(warehouse.toString());
        for (JsonNode node : purger.getAll(uri, "roles")) {
            String role = node.get("name").toString().replace("\"", "");
            logger.info("Deleting role {} from catalog {}", role, warehouse);
            client.delete(String.format("%s/%s", uri, role), accessToken);
        }
    }

    public static void main(String[] args) {
        String                      host = args[0];
        PurgeAndPopulatePolarisMain app  = new PurgeAndPopulatePolarisMain(host);
        try {
            if (args.length == 1) {
                app.createAllCatalogs();
            } else if (args.length == 4) {
                AfonCloud warehouse = valueOf(args[1]);
                app.purger.cleanTable(warehouse.toString(), args[2], args[3]);
            }
        } catch (Exception e) {
            logger.error("Failed to purge and populate polaris for host {}", host, e);
            throw new RuntimeException(e);
        }
    }

}
