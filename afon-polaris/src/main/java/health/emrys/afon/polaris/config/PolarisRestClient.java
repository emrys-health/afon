package health.emrys.afon.polaris.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.client5.http.classic.methods.HttpGet;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static java.lang.String.format;

/**
 * Closes the HTTP connection on each call.
 * So, not efficient but it won't leak resources.
 * Since you won't make many calls, this is reasonable,
 */
public class PolarisRestClient {

    static final Logger logger       = org.slf4j.LoggerFactory.getLogger(PolarisRestClient.class);
    static final String CONTENT_TYPE = "Content-Type";

    private final String endpoint;
    protected final String clientId;
    protected final String clientSecret;

    public PolarisRestClient(String endpoint, String clientId, String clientSecret) {
        this.endpoint     = endpoint;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Odd. This con sometimes receive a HTTP 503 even though the Polaris server logs HTTP 200.
     */
    public String getAccessToken() {
        int attempt = 3;
        IOException lastException = null;
        while (attempt > 0) {
            try {
                return accessToken();
            } catch (IOException e) {
                lastException = e;
                logger.error(e.toString());
                if (attempt-- > 0) {
                    try {
                        int millis = 5000;
                        logger.info("Sleeping {}ms before retry...",  millis);
                        Thread.sleep(millis);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
        throw new RuntimeException(lastException);
    }

    public String accessToken() throws IOException {
        String uri = PolarisConfigurer.tokensURI(endpoint);
        logger.info("Calling " + uri);
        HttpPost post = new HttpPost(uri);
        post.setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");
        post.setHeader("Polaris-Realm", "POLARIS");
        logger.info("client id = {}, secret = {}", clientId, clientSecret);
        String body = format("grant_type=client_credentials&client_id=%s&client_secret=%s&scope=PRINCIPAL_ROLE:ALL", clientId, clientSecret);
        ClassicHttpResponse response = post(body, post);
        return parseAccessToken(response);
    }

    public ClassicHttpResponse delete(String uri, String accessToken) throws IOException {
        logger.info("Deleting with uri {} at {}", uri, endpoint);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpDelete          delete  = new HttpDelete(endpoint + uri);
            addAccessTokenToHeaders(delete, accessToken);
            ClassicHttpResponse response = (ClassicHttpResponse) client.execute(delete);
            String              content  = readContent(response);
            logger.info("{}:{}:{}", delete, response.getCode(), content);
            return response;
        }
    }

    private @NonNull String readContent(ClassicHttpResponse response) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteArrayInputStream  bais = null;
        try {
            if (response.getEntity() != null) {
                response.getEntity().writeTo(baos);
                bais = new ByteArrayInputStream(baos.toByteArray());
                return new String(bais.readAllBytes());
            }
        }  finally {
            if (bais != null) bais.close();
            baos.close();
        }
        return "NO CONTENT";
    }

    public ClassicHttpResponse put(String uri,
                                   String json,
                                   String accessToken) throws IOException {
        return post(json, new HttpPut(endpoint + uri), accessToken);
    }

    public ClassicHttpResponse post(String uri,
                                    String json,
                                    String accessToken) throws IOException {
        String url = endpoint + uri;
        logger.info("Calling " + url + " with " + json);
        return post(json, new HttpPost(url), accessToken);
    }

    public ClassicHttpResponse get(String url,
                                   String accessToken) throws IOException {
        String  uri     = endpoint + url;
        logger.info("Calling {}", url);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(uri);
            addAccessTokenToHeaders(request, accessToken);
            ClassicHttpResponse response = client.execute(request);
            logger.info(format("%s:%d", request, response.getCode()));
            return response;
        }
    }

    private ClassicHttpResponse post(String payload,
                                     BasicClassicHttpRequest post,
                                     String accessToken) throws IOException {
        addAccessTokenToHeaders(post, accessToken);
        post.setHeader("Accept", "application/json");
        post.setHeader(CONTENT_TYPE, "application/json");
        return post(payload, post);
    }

    private static void addAccessTokenToHeaders(BasicClassicHttpRequest request, String accessToken) {
        request.setHeader("Authorization", "Bearer " + accessToken);
    }

    protected static String parseAccessToken(ClassicHttpResponse response) throws IOException {
        ObjectMapper        mapper                  = JsonMapper
            .builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();
        Map<String, Object> tokenResponse           = mapper.readValue(response.getEntity().getContent(), Map.class);
        String              accessTokenFromResponse = (String) tokenResponse.get("access_token");
        return accessTokenFromResponse;
    }

    protected ClassicHttpResponse post(String payload,
                                       BasicClassicHttpRequest post) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            post.setEntity(new StringEntity(payload));
            ClassicHttpResponse response = (ClassicHttpResponse) client.execute(post);
            logger.info(format("%s:%d", post, response.getCode()));
            assert response.getCode() == 200;
            return response;
        }
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getEndpoint() {
        return endpoint;
    }

}
