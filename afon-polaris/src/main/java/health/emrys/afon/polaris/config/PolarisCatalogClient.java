package health.emrys.afon.polaris.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

import static health.emrys.afon.polaris.config.CatalogConfigurer.*;
import static health.emrys.afon.polaris.config.PolarisRestClient.failuresOf;
import static health.emrys.afon.polaris.config.PolarisRestClient.isFailure;

public class PolarisCatalogClient {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisCatalogClient.class);

    private final AfonPropertyProvider properties;
    private final PolarisCatalog[] catalogs;
    private final CatalogConfigurer configurer;
    private final PolarisRestClient client;
    private final String accessToken;
    private final String domainName;

    private static final Map<AfonCloud, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new,
            AfonCloud.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

    public PolarisCatalogClient(AfonPropertyProvider properties) {
        this.properties = properties;
        this.catalogs = catalogs(properties);
        this.client = PolarisRestClientFactory.clientFactory().createClient(properties);
        this.configurer = new CatalogConfigurer(client);
        this.accessToken = client.getAccessToken();
        this.domainName = client.getEndpoint();
    }

    private PolarisCatalog[] catalogs(AfonPropertyProvider properties) {
        return new PolarisCatalog[]{
                new AwsPolarisCatalog(properties),
                new GcpPolarisCatalog(properties),
                new AzurePolarisCatalog(properties),
                new GlueAwsPolarisCatalog(properties),
                new AzureExternalPolarisCatalog(properties),
                new AwsForbiddenPolarisCatalog(properties)
        };
    }

    public List<HttpResponse> configureAllCatalogs() throws IncorrectConfigException, IOException {
        return configureCatalogs(catalogs);
    }

    public List<HttpResponse> configureCatalogs(String... catalogNames) throws IOException, IncorrectConfigException {
        return configureCatalogs(catalogsOf(catalogNames));
    }

    public List<HttpResponse> configureCatalogs(PolarisCatalog... catalogs) throws IOException, IncorrectConfigException {
        List<String> catalogNames = catalogNames(catalogs);
        logger.info("Configuring catalogs: {}", String.join(", ", catalogNames));
        List<HttpResponse> allResponses = new ArrayList<HttpResponse>();
        for (PolarisCatalog catalog : catalogs) {
            allResponses.addAll(configurer.configureCatalog(catalog));
        }
        checkFailures(catalogNames, allResponses);
        return allResponses;
    }

    private PolarisCatalog[] catalogsOf(String... names) {
        return Arrays.stream(names).map(name -> catalogOf(name)).toArray(PolarisCatalog[]::new);
    }

    private PolarisCatalog catalogOf(String name) {
        return CLOUD_TO_CATALOG.get(AfonCloud.valueOf(name.toUpperCase())).apply(properties);
    }

    private List<String> catalogNames(PolarisCatalog... catalogs) {
        return Arrays.stream(catalogs).map(PolarisCatalog::catalogName).toList();
    }

    private void checkFailures(List<String> catalogNames, List<HttpResponse> responses) {
        List<String> failures = failuresOf(responses);
        if (!failures.isEmpty()) {
            String message = String.format("Failed to configure catalogs %s with failures %s", catalogNames, failures);
            logger.warn(message);
            throw new RuntimeException(message);
        }
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
        String url = tablesURI(catalogName.toString(), namespace);
        List<HttpResponse> responses = new ArrayList<>();
        for (JsonNode node : getNodes(url, "identifiers")) {
            String table = node.get("name").toString();
            responses.add(deleteTable(catalogName, namespace, table.replace("\"", "")));
        }
        return responses;
    }

    public HttpResponse deleteTable(String catalogName, String namespace, String table) throws IOException {
        logger.info("Deleting table {} from {}.{} in {}", table, catalogName, namespace, domainName);
        HttpResponse response = client.delete(tableURI(catalogName.toString(), namespace, table), accessToken);
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