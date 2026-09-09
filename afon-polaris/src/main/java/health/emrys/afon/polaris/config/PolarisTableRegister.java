package health.emrys.afon.polaris.config;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.polaris.config.PolarisRestClient.logIfFailure;
import static java.lang.String.format;

public class PolarisTableRegister {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisTableRegister.class);

    private PolarisRestClient client;

    public PolarisTableRegister(PolarisRestClient client){
        this.client = client;
    }

    public List<HttpResponse> createTable(String catalog, String namespace, String table, String metadata) throws IOException {
        logger.info("Creating table {}.{}.{} in catalog {}", namespace, table, metadata, catalog);
        String uri = format("/api/catalog/v1/%s/namespaces/%s/register", catalog, namespace);
        String json = format("""
                {
                    "name" : "%s",
                    "metadata-location": "%s"
                }
                """, table, metadata);
        ClassicHttpResponse response = client.post(uri, json, client.getAccessToken());
        String description = String.format(
                "create table %s.%s.%s.%s",
                catalog, namespace, table, metadata);

        logIfFailure(response, description);
        return List.of(response);
    }

    public List<HttpResponse> createNamespace(String catalog, String namespace) throws IOException {
        logger.info("Creating namespace {} in catalog {}", namespace, catalog);
        String uri = format("/api/catalog/v1/%s/namespaces", catalog);
        String json = format("""
                {
                    "namespace" : ["%s"]
                }
                """, namespace);
        ClassicHttpResponse response = client.post(uri, json, client.getAccessToken());
        String description = String.format("create namespace %s.%s", catalog, namespace);
        logIfFailure(response, description);
        return List.of(response);
    }

}
