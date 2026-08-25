package health.emrys.afon.polaris.config;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.polaris.config.PolarisRestClient.isFailure;
import static java.lang.String.format;

public class PolarisTableRegister {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisTableRegister.class);

    private PolarisRestClient client;

    public PolarisTableRegister(PolarisRestClient client){
        this.client = client;
    }

    public List<String> createTable(String catalog, String namespace, String table, String metadata) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces/%s/register", catalog, namespace);
        String json = format("""
                {
                    "name" : "%s",
                    "metadata-location": "%s"
                }
                """, table, metadata);
        logger.info(json);
        ClassicHttpResponse response = client.post(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
        if (isFailure(response)) {
            String error = String.format(
                    "HTTP code %d table %s.%s.%s.%s",
                    response.getCode(),
                    catalog, namespace, table, metadata);
            logger.error(error);
            return List.of(error);
        }
        return List.of();
    }

    public List<String> createNamespace(String catalog, String namespace) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces", catalog);
        String json = format("""
                {
                    "namespace" : ["%s"]
                }
                """, namespace);
        logger.info(json);
        ClassicHttpResponse response = client.post(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
        if (isFailure(response)) {
            String error = String.format(
                    "HTTP code %d when creating namespace %s.%s",
                    response.getCode(), catalog, namespace);
            logger.error(error);
            return List.of(error);
        }
        return List.of();
    }
}
