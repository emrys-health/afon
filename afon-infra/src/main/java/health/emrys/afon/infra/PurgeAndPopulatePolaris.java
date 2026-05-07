package health.emrys.afon.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;
import static health.emrys.afon.infra.checks.SparkChecker.EXTERNAL_NAMESPACE;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;
import static health.emrys.afon.polaris.comms.PolarisConfigurer.*;
import static org.slf4j.LoggerFactory.getLogger;

public class PurgeAndPopulatePolaris {

    private static final Logger log = getLogger(PurgeAndPopulatePolaris.class);

    public static void main(String[] args) throws Exception {
        String host = args[0];
        PurgeAndPopulatePolaris app = new PurgeAndPopulatePolaris(host);
        if (args.length == 1) {
            app.createAllCatalogsFor();
        } else if (args.length == 4) {
            app.clean(SupportedCloudsEnum.valueOf(args[1]), args[2], args[3]);
        }
    }

    public PurgeAndPopulatePolaris(String domainName) {
        this.domainName = domainName;
    }

    private final String domainName;

    public void createAllCatalogsFor() throws IncorrectConfigException, IOException {
        purgeAcceptanceTests(domainName);
        purgeRecursivelyForAllCatalogs(domainName, EXTERNAL_NAMESPACE);
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        config.initializePolaris();
    }

    public void clean(SupportedCloudsEnum warehouse,
                      String namespace,
                      String table) throws IOException {
        log.info("Attempting to delete table {} in warehouse {} from namespace {} in {}",
                 table, warehouse, namespace, domainName);
        var    client      = new AfonPolarisClient(domainName, new AfonPropertiesLoader().getProperties());
        String accessToken = client.getAccessToken();
        client.delete(getDeleteTableURI(warehouse.toString(), namespace, table), accessToken);
        client.delete(getDeleteNamespaceURI(warehouse.toString(), namespace), accessToken);
        client.delete(getDeleteURI(warehouse.toString()), accessToken);
    }

    public void purgeAcceptanceTests(String domainName) {
        purgeRecursivelyForAllCatalogs(domainName, ACCEPTANCE_TEST_NAMESPACE);
    }

    private void purgeRecursivelyForAllCatalogs(String domainName, String namespace) {
        log.info("Deleting namespace {} from {} for all warehouses if they're present",
                 namespace, domainName);
        var client = new AfonPolarisClient(domainName, new AfonPropertiesLoader().getProperties());

        for (SupportedCloudsEnum warehouse : List.of(AZURE, AWS, GCP)) {
            try {
                purgeAllTablesIn(domainName, warehouse, client, namespace);
            } catch (IOException | ParseException e) {
                log.warn("Failed to delete {} namespace in catalog {} in {}. Ignoring and continuing.",
                         namespace, warehouse, domainName);
            }
        }
    }

    private void purgeAllTablesIn(String domainName,
                                  SupportedCloudsEnum warehouse,
                                  AfonPolarisClient client,
                                  String namespace) throws IOException, ParseException {
        ObjectMapper        mapper      = new ObjectMapper();
        String              accessToken = client.getAccessToken();
        ClassicHttpResponse response    = client.getCall(listTables(warehouse.toString(), namespace), accessToken);
        JsonNode            root        = mapper.readTree(EntityUtils.toString(response.getEntity()));
        JsonNode            identifiers = root.get("identifiers");

        if (identifiers != null && identifiers.isArray()) {
            for (JsonNode node : identifiers) {
                String table = node.get("name").toString();
                clean(warehouse, namespace, table.replace("\"", ""));
            }
        }
    }

}
