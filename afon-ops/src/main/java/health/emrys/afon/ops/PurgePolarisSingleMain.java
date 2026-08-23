package health.emrys.afon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
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

import static health.emrys.afon.cluster.ClusterUtils.isFailure;
import static health.emrys.afon.polaris.config.PolarisConfigurer.*;

/**
 * Ths was created in a rush for the demo TODO refactor the code copy-and-pasted from PurgeAndPopulatePolarisMain
 */
public class PurgePolarisSingleMain {
    private static final Logger logger = LoggerFactory.getLogger(PurgePolarisMain.class);

    private final PolarisRestClient client;
    private final String            accessToken;
    private final String            domainName;
    private final ObjectMapper      mapper = new ObjectMapper();
    private final AfonPropertyProvider properties;

    private PurgePolarisSingleMain(AfonPropertyProvider properties) {
        this.properties = properties;
        this.domainName = properties.getValue(AfonProperty.POLARIS_ENDPOINT);
        client          = PolarisRestClientFactory.clientFactory().createClient(properties);
        accessToken     = client.getAccessToken();
    }

    private void purge(String catalogName) throws IOException, ParseException {
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

    private Iterable<JsonNode> getAll(
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

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.printf("%s requires arguments: domain-name catalog-name", PurgePolarisSingleMain.class.getSimpleName());
            return;
        }
        String domainName  = args[0];
        String catalogName = args[1];
        var properties = new FilePropertyProvider();
        properties.setValue(AfonProperty.POLARIS_ENDPOINT, domainName);
        try {
            new PurgePolarisSingleMain(properties).purge(catalogName);
        } catch (Exception e) {
            logger.error("Failed to purge Polaris for domain {}", domainName, e);
            throw new RuntimeException(e);
        }
    }


}
