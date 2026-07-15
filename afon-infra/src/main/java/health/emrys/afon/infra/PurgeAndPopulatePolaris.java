package health.emrys.afon.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.AfonPolarisClientFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;
import static health.emrys.afon.infra.InfraUtils.isFailure;
import static health.emrys.afon.polaris.comms.PolarisConfigurer.*;
import static org.slf4j.LoggerFactory.getLogger;

public class PurgeAndPopulatePolaris {

    private static final Logger log = getLogger(PurgeAndPopulatePolaris.class);

    public static void main(String[] args) throws Exception {
        String                  host = args[0];
        PurgeAndPopulatePolaris app  = new PurgeAndPopulatePolaris(host);
        if (args.length == 1) {
            app.createAllCatalogs();
        } else if (args.length == 4) {
            app.cleanTable(SupportedCloudsEnum.valueOf(args[1]), args[2], args[3]);
        }
    }

    private final AfonPolarisClient client;
    private final String            accessToken;

    public PurgeAndPopulatePolaris(String domainName) {
        AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
        this.domainName  = domainName;
        this.client      = clientFactory.getPolarisClient(domainName, new AfonPropertiesLoader().getProperties());
        this.accessToken = client.getAccessToken();
        log.debug("For {}/{}:\nPOLARIS_TOKEN={}", client.getClientId(), client.getClientSecret(), accessToken);
    }

    private final String       domainName;
    private final ObjectMapper mapper = new ObjectMapper();

    public void createAllCatalogs() throws IncorrectConfigException, IOException {
        purgeRecursivelyForAllCatalogs(domainName);
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        config.initializePolaris();
    }

    private List<HttpResponse> cleanAllTables(
        SupportedCloudsEnum warehouse,
        String namespace) throws IOException, ParseException {
        log.info("Attempting to delete tables from {}.{}", warehouse, namespace);
        String url = listTables(warehouse.toString(), namespace);
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
            log.warn("Calling {} did not yield JSON with a root node of {}", url, rootNodeName);
            return Collections.emptyList();
        }
        return identifiers;
    }

    public HttpResponse cleanTable(
        SupportedCloudsEnum warehouse,
        String namespace,
        String table) throws IOException {
        log.info(
            "Attempting to delete table {} in warehouse {} from namespace {} in {}",
            table, warehouse, namespace, domainName);
        String       accessToken = client.getAccessToken();
        HttpResponse response    = client.delete(tableURI(warehouse.toString(), namespace, table), accessToken);
        if (isFailure(response)) {
            log.warn("Could not delete table {} in {}",  table, warehouse);
        }
        return response;
    }

    private void purgeRecursivelyForAllCatalogs(String domainName) {
        for (SupportedCloudsEnum warehouse : List.of(AZURE, AWS, GCP)) {
            try {
                for (JsonNode namespaces : getAll(namespaceURI(warehouse.toString()), "namespaces")) {
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
                log.warn(
                    "Failed to delete catalog {} in {}. Error is {}. Ignoring and continuing.",
                    warehouse, domainName, e.getMessage());
            }
        }
    }

    private void purgeRolesOf(SupportedCloudsEnum warehouse) throws IOException, ParseException {
        String uri = catalogRolesURI(warehouse.toString());
        for (JsonNode node : getAll(uri, "roles")) {
            String role = node.get("name").toString().replace("\"", "");
            log.info("Deleting role {} from catalog {}", role, warehouse);
            client.delete(String.format("%s/%s", uri, role), accessToken);
        }
    }

}
