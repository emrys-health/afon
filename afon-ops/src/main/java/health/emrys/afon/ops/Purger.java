package health.emrys.afon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.polaris.config.PolarisRestClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static health.emrys.afon.polaris.config.PolarisConfigurer.*;

public class Purger {

    private static final Logger logger = LoggerFactory.getLogger(Purger.class);

    private final PolarisRestClient client;
    private final String            accessToken;
    private final String            domainName;
    private final ObjectMapper      mapper = new ObjectMapper();

    public Purger(PolarisRestClient client, String domainName) {
        this.client      = client;
        this.accessToken = client.getAccessToken();
        this.domainName  = domainName;
    }

    public void deleteCatalog(String catalogName) throws IOException, ParseException {
        purge(catalogName);
        deleteRoles(catalogName);
        client.delete(catalogsURI(catalogName), accessToken);
    }

    private void deleteRoles(String catalogName) throws IOException, ParseException {
        String uri = catalogRolesURI(catalogName);
        for (JsonNode node : getNodes(uri, "roles")) {
            String roleName = node.get("name").toString().replace("\"", "");
            logger.info("Deleting role {} from catalog {}", roleName, catalogName);
            client.delete(String.format("%s/%s", uri, roleName), accessToken);
        }
    }

    private Iterable<JsonNode> getNodes(String url, String rootNode) throws IOException, ParseException {
        logger.info("Retrieving nodes from url {} with root node {}", url, rootNode);
        ClassicHttpResponse response = client.get(url, accessToken);
        JsonNode root = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        JsonNode identifiers = root.get(rootNode);
        if (identifiers == null || !identifiers.isArray()) {
            logger.warn("No nodes found from url {} with root node of {}", url, rootNode);
            return Collections.emptyList();
        }
        return identifiers;
    }

    public void purge(String catalogName) throws IOException, ParseException {
        for (JsonNode namespaces : getAll(namespacesURI(catalogName), "namespaces")) {
            if (namespaces.isArray()) {
                for (JsonNode namespace : namespaces) {
                    String namespaceName = namespace.textValue();
                    cleanAllTables(catalogName, namespaceName);
                    if (isFailure(client.delete(namespaceURI(catalogName, namespaceName), accessToken))) {
                        throw new IllegalStateException(String.format("Could not delete namespace %s from %s", namespaceName, catalogName));
                    }
                }
            }
        }
        client.delete(catalogsURI(catalogName), accessToken);
    }

    private List<HttpResponse> cleanAllTables(
        String warehouse,
        String namespace
    ) throws IOException, ParseException {
        logger.info("Attempting to delete tables from {}.{}", warehouse, namespace);
        String             url       = tablesURI(warehouse.toString(), namespace);
        List<HttpResponse> responses = new ArrayList<>();
        for (JsonNode node : getAll(url, "identifiers")) {
            String table = node.get("name").toString();
            responses.add(cleanTable(warehouse, namespace, table.replace("\"", "")));
        }
        return responses;
    }

    public HttpResponse cleanTable(
        String warehouse,
        String namespace,
        String table
    ) throws IOException {
        logger.info(
            "Attempting to delete table {} in warehouse {} from namespace {} in {}",
            table, warehouse, namespace, domainName);
        String       accessToken = client.getAccessToken();
        HttpResponse response    = client.delete(tableURI(warehouse.toString(), namespace, table), accessToken);
        if (isFailure(response)) {
            logger.warn("Could not delete table {} in {}", table, warehouse);
        }
        return response;
    }

    public Iterable<JsonNode> getAll(
        String url,
        String rootNodeName
    ) throws IOException, ParseException {
        ClassicHttpResponse response    = client.get(url, accessToken);
        JsonNode            root        = mapper.readTree(EntityUtils.toString(response.getEntity()));
        JsonNode            identifiers = root.get(rootNodeName);
        if (identifiers == null || !identifiers.isArray()) {
            logger.warn("Calling {} did not yield JSON with a root node of {}", url, rootNodeName);
            return Collections.emptyList();
        }
        return identifiers;
    }
}
