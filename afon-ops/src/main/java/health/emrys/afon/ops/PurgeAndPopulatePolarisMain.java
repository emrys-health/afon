package health.emrys.afon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static health.emrys.afon.cluster.ClusterUtils.isFailure;
import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.polaris.config.PolarisConfigurer.*;
import static org.slf4j.LoggerFactory.getLogger;

public class PurgeAndPopulatePolarisMain {

    private static final Logger logger = getLogger(PurgeAndPopulatePolarisMain.class);

    private final AfonPolarisClient client;
    private final String            accessToken;

    public PurgeAndPopulatePolarisMain(String domainName) {
        AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
        this.domainName  = domainName;
        this.client      = clientFactory.getPolarisClient(domainName, new FilePropertyProvider().getProperties());
        this.accessToken = client.getAccessToken();
        logger.debug("For {}/{}:\nPOLARIS_TOKEN={}", client.getClientId(), client.getClientSecret(), accessToken);
    }

    private final String       domainName;
    private final ObjectMapper mapper = new ObjectMapper();

    public void createAllCatalogs() throws IncorrectConfigException, IOException {
        purgeRecursivelyForAllCatalogs(domainName);
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        config.initializePolaris();
    }

    private List<HttpResponse> cleanAllTables(
        AfonCloud warehouse,
        String namespace) throws IOException, ParseException {
        logger.info("Attempting to delete tables from {}.{}", warehouse, namespace);
        String url = tablesURI(warehouse.toString(), namespace);
        List<HttpResponse> responses = new ArrayList<>();
        for (JsonNode node : getAll(url, "identifiers")) {
            String table = node.get("name").toString();
            responses.add(cleanTable(warehouse, namespace, table.replace("\"", "")));
        }
        return responses;
    }

    private Iterable<JsonNode> getAll(
        String url,
        String rootNodeName) throws IOException, ParseException {
        ClassicHttpResponse response    = client.getCall(url, accessToken);
        JsonNode            root        = mapper.readTree(EntityUtils.toString(response.getEntity()));
        JsonNode            identifiers = root.get(rootNodeName);
        if (identifiers == null || !identifiers.isArray()) {
            logger.warn("Calling {} did not yield JSON with a root node of {}", url, rootNodeName);
            return Collections.emptyList();
        }
        return identifiers;
    }

    public HttpResponse cleanTable(
        AfonCloud warehouse,
        String namespace,
        String table) throws IOException {
        logger.info(
            "Attempting to delete table {} in warehouse {} from namespace {} in {}",
            table, warehouse, namespace, domainName);
        String       accessToken = client.getAccessToken();
        HttpResponse response    = client.delete(tableURI(warehouse.toString(), namespace, table), accessToken);
        if (isFailure(response)) {
            logger.warn("Could not delete table {} in {}",  table, warehouse);
        }
        return response;
    }

    private void purgeRecursivelyForAllCatalogs(String domainName) {
        for (AfonCloud warehouse : List.of(AZURE, AWS, GCP)) {
            try {
                for (JsonNode namespaces : getAll(namespacesURI(warehouse.toString()), "namespaces")) {
                    if (namespaces.isArray()) {
                        for (JsonNode namespace : namespaces) {
                            String namespaceName = namespace.textValue();
                            cleanAllTables(warehouse, namespaceName);
                            if (isFailure(client.delete(namespaceURI(warehouse.toString(), namespaceName), accessToken))) {
                                throw new IllegalStateException(String.format("Could not delete namespace %s from %s", namespaceName, warehouse));
                            }
                        }
                    }
                }
                client.delete(catalogsURI(warehouse.toString()), accessToken);
                purgeRolesOf(warehouse);
            } catch (IOException | ParseException e) {
                logger.warn(
                    "Failed to delete catalog {} in {}. Error is {}. Ignoring and continuing.",
                    warehouse, domainName, e.getMessage());
            }
        }
    }

    private void purgeRolesOf(AfonCloud warehouse) throws IOException, ParseException {
        String uri = catalogRolesURI(warehouse.toString());
        for (JsonNode node : getAll(uri, "roles")) {
            String role = node.get("name").toString().replace("\"", "");
            logger.info("Deleting role {} from catalog {}", role, warehouse);
            client.delete(String.format("%s/%s", uri, role), accessToken);
        }
    }

    public static void main(String[] args) {
        String                  host = args[0];
        PurgeAndPopulatePolarisMain app  = new PurgeAndPopulatePolarisMain(host);
        try {
            if (args.length == 1) {
                app.createAllCatalogs();
            } else if (args.length == 4) {
                app.cleanTable(AfonCloud.valueOf(args[1]), args[2], args[3]);
            }
        } catch (Exception e) {
            logger.error("Failed to purge and populate polaris for host {}", host, e);
            throw new RuntimeException(e);
        }
    }

}
