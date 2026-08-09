package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;

import static java.lang.String.format;

public class RegisterTableConfig {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterTableConfig.class);

    private final AfonCloud catalog;
    private final String namespace;
    private final String table;
    private final String metadata;

    public RegisterTableConfig(AfonCloud catalog, String namespace, String table, String metadata) {
        this.catalog = catalog;
        this.namespace = namespace;
        this.table = table;
        this.metadata = metadata;
    }

    public ClassicHttpResponse createTable(AfonPolarisClient client) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces/%s/register", catalog, namespace);
        String json = format("""
                {
                    "name" : "%s",
                    "metadata-location": "%s"
                }
                """, table, metadata);
        logger.info(json);
        ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
        return response;
    }

    public ClassicHttpResponse createNamespace(AfonPolarisClient client) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces", catalog);
        String json = format("""
                {
                    "namespace" : ["%s"]
                }
                """, namespace);
        logger.info(json);
        ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
        return response;
    }

    public String getTable() {
        return table;
    }

    public String getNamespace() {
        return namespace;
    }

    public AfonCloud getCatalog() {
        return catalog;
    }
}
