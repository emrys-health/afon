package health.emrys.afon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static health.emrys.afon.polaris.config.PolarisRestClient.isFailure;
import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.config.PolarisConfigurer.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * TODO: Compare with {@link DeletePolarisCatalogsMain} and remove whichever is the more redundant class.
 */
public class ManagePolarisCatalogsMain {

    private static final Logger logger = getLogger(ManagePolarisCatalogsMain.class);

    private final AfonPropertyProvider properties;
    private final PolarisRestClient client;
    private final String endpoint;
    private final String accessToken;

    public ManagePolarisCatalogsMain(AfonPropertyProvider properties) {
        this.properties = properties;
        this.endpoint = properties.getValue(POLARIS_ENDPOINT);
        this.client = PolarisRestClientFactory.clientFactory().createClient(properties);
        this.accessToken = client.getAccessToken();
        logger.debug("Polaris client for endpoint {}: clientId={}, clientSecret={}, token={}", endpoint, client.getClientId(), client.getClientSecret(), accessToken);
    }

    public void createAllCatalogs() throws IncorrectConfigException, IOException {
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(properties);
        config.configure();
    }

    public void deleteAllCatalogs() throws IOException, ParseException {
        for (AfonCloud id : List.of(AZURE, AWS, GCP)) {
            deleteCatalog(id.name());
        }
    }

    public void deleteCatalog(String catalogName) throws IOException, ParseException {
        for (JsonNode namespaces : getNodes(namespacesURI(catalogName), "namespaces")) {
            if (namespaces.isArray()) {
                for (JsonNode namespace : namespaces) {
                    String namespaceName = namespace.textValue();
                    deleteAllTables(catalogName, namespaceName);
                    String uri = namespaceURI(catalogName, namespaceName);
                    if (isFailure(client.delete(uri, accessToken))) {
                        throw new IllegalStateException(String.format("Could not delete namespace %s from %s", namespaceName, catalogName));
                    }
                }
            }
        }
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

    private List<HttpResponse> deleteAllTables(String catalogName, String namespace) throws IOException, ParseException {
        logger.info("Deleting tables from namespace {}.{}", catalogName, namespace);
        String uri = tablesURI(catalogName, namespace);
        List<HttpResponse> responses = new ArrayList<>();
        for (JsonNode node : getNodes(uri, "identifiers")) {
            String tableName = node.get("name").toString();
            responses.add(deleteTable(catalogName, namespace, tableName.replace("\"", "")));
        }
        return responses;
    }

    public HttpResponse deleteTable(String catalogName, String namespace, String table) throws IOException {
        logger.info("Deleting table {} from namespace {}.{}", table, catalogName, namespace);
        String uri = tableURI(catalogName, namespace, table);
        HttpResponse response = client.delete(uri, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to delete table {} from namespace {}.{}", table, catalogName, namespace);
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

    public static void main(String[] args) {
        String action;
        String domainName;
        String catalogName = null;
        String namespace = null;
        String tableName = null;
        if (args.length == 2 ) {
            action = args[0];
            domainName = args[1];
        } else if (args.length == 3 ) {
            action = args[0];
            domainName = args[1];
            catalogName = args[2];
        } else if (args.length == 5 ) {
            action = args[0];
            domainName = args[1];
            catalogName = args[2];
            namespace = args[3];
            tableName = args[4];
        } else {
            System.out.printf("%s requires arguments: [delete|create|recreate] domain-name", ManagePolarisCatalogsMain.class.getSimpleName());
            System.out.printf("%s requires arguments: [delete] domain-name catalog-name", ManagePolarisCatalogsMain.class.getSimpleName());
            System.out.printf("%s requires arguments: [delete] domain-name catalog-name namespace table-name", ManagePolarisCatalogsMain.class.getSimpleName());
            throw new IllegalArgumentException();
        }

        var properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, domainName);
        ManagePolarisCatalogsMain app = new ManagePolarisCatalogsMain(properties);
        try {
            if ( action.equals("delete") ) {
                if ( catalogName == null ) {
                    logger.info("Deleting all catalogs for domain {}", domainName);
                    app.deleteAllCatalogs();
                } else {
                    logger.info("Deleting catalog {} for domain {}", catalogName, domainName);
                    app.deleteCatalog(catalogName);
                }
                if ( namespace != null &&  tableName != null ) {
                    logger.info("Deleting table {}.{} for catalog {} and domain {}", namespace, tableName, catalogName, domainName);
                    app.deleteTable(catalogName, namespace, tableName);
                }
            }
            if ( action.equals("create") ) {
                logger.info("Creating all catalogs for domain {}", domainName);
                app.createAllCatalogs();
            }
            if ( action.equals("recreate") ) {
                logger.info("Deleting and recreating all catalogs for domain {}", domainName);
                app.deleteAllCatalogs();
                app.createAllCatalogs();
            }
        } catch (Exception e) {
            logger.error("Failed {} action with args {}", ManagePolarisCatalogsMain.class.getSimpleName(), List.of(args), e);
            throw new RuntimeException(e);
        }
    }

}
