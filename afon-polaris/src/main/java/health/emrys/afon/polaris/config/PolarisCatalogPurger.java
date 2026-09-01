package health.emrys.afon.polaris.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static health.emrys.afon.polaris.config.PolarisRestClient.isFailure;

public class PolarisCatalogPurger {

    private static final Logger logger = LoggerFactory.getLogger(PolarisCatalogPurger.class);

    private final PolarisRestClient client;
    private final String            accessToken;
    private final String            domainName;

    public PolarisCatalogPurger(PolarisRestClient client, String domainName) {
        this.client      = client;
        this.accessToken = client.getAccessToken();
        this.domainName  = domainName;
    }

    public void deleteCatalog(String catalogName) throws IOException, ParseException {
        purgeNamespacesAndTables(catalogName);
        purgeCatalog(catalogName);
        purgeRoles(catalogName);
    }

    private void purgeRoles(String catalogName) throws IOException, ParseException {
        logger.info("Deleting roles from catalog {}", catalogName);
        String uri = catalogRolesURI(catalogName);
        for (JsonNode node : getNodes(uri, "roles")) {
            String roleName = node.get("name").toString().replace("\"", "");
            logger.info("Deleting role {} from catalog {}", roleName, catalogName);
            client.delete(String.format("%s/%s", uri, roleName), accessToken);
        }
    }

    private void purgeNamespacesAndTables(String catalogName) throws IOException, ParseException {
        for (JsonNode namespaces : getNodes(namespacesURI(catalogName), "namespaces")) {
            if (namespaces.isArray()) {
                for (JsonNode node : namespaces) {
                    String namespace = node.textValue();
                    deleteAllTables(catalogName, namespace);
                    deleteNamespace(catalogName, namespace);
                }
            }
        }
    }

    private void purgeCatalog(String catalogName) throws IOException {
        logger.info("Deleting catalog {}", catalogName);
        HttpResponse delete = client.delete(catalogsURI(catalogName), accessToken);
        if (isFailure(delete)) {
            logger.warn("Could not delete catalog {}", catalogName);
        }
    }

    public void deleteNamespace(String catalogName, String namespace) throws IOException {
        logger.info("Deleting namespace {} from catalog {}", namespace, catalogName);
        HttpResponse delete = client.delete(namespaceURI(catalogName, namespace), accessToken);
        if (isFailure(delete)) {
            logger.warn("Could not delete namespace {} from {}", namespace, catalogName);
        }
    }

    public List<HttpResponse> deleteAllTables(String catalogName, String namespace) throws IOException, ParseException {
        logger.info("Deleting tables from {}.{}", catalogName, namespace);
        String             url       = tablesURI(catalogName.toString(), namespace);
        List<HttpResponse> responses = new ArrayList<>();
        for (JsonNode node : getNodes(url, "identifiers")) {
            String table = node.get("name").toString();
            responses.add(deleteTable(catalogName, namespace, table.replace("\"", "")));
        }
        return responses;
    }

    public HttpResponse deleteTable(String catalogName, String namespace, String table) throws IOException {
        logger.info("Deleting table {} from {}.{} in {}", table, catalogName, namespace, domainName);
        String       accessToken = client.getAccessToken();
        HttpResponse response    = client.delete(tableURI(catalogName.toString(), namespace, table), accessToken);
        if (isFailure(response)) {
            logger.warn("Could not delete table {} in {}", table, catalogName);
        }
        return response;
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

}
