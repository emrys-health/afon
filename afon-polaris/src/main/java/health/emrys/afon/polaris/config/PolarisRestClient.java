package health.emrys.afon.polaris.config;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.security.HttpResponses.logOutcome;
import static health.emrys.afon.security.HttpResponses.parseAccessToken;
import static java.lang.String.format;

/**
 *  A client implementing the REST commands (DELETE, GET, PUT, POST) on Polaris URIs.
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
        this.clientSecret = ***REMOVED***
    }

    public static @NonNull String tokensURI(String endpoint) {
        return format("%s/api/catalog/v1/oauth/tokens", endpoint);
    }

    /**
     * Odd. This con sometimes receive a HTTP 503 even though the Polaris server logs HTTP 200.
     * Hence I/O and server (5xx) errors are retried, while other failures (e.g. 401) fail immediately.
     */
    public String getAccessToken() {
        int attempt = 3;
        IOException lastException = null;
        while (attempt > 0) {
            try {
                String accessToken = accessToken();
                logger.info("Access token retrieved for client {}", clientId);
                return accessToken;
            } catch (IOException e) {
                lastException = e;
                logger.error(e.toString());
                if (--attempt > 0) {
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

    protected String accessToken() throws IOException {
        String uri = tokensURI(endpoint);
        logger.info("Access token from {}", uri);
        HttpPost post = new HttpPost(uri);
        post.setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");
        post.setHeader("Polaris-Realm", "POLARIS");
        String body = format("grant_type=client_credentials&client_id=%s&client_secret=***REMOVED***", clientId, clientSecret);
        ClassicHttpResponse response = post(body, post);
        logOutcome(post, response);
        return parseAccessToken(response);
    }

    public ClassicHttpResponse delete(String uri, String accessToken) throws IOException {
        logger.info("DELETE {} at {}", uri, endpoint);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = endpoint + uri;
            HttpDelete delete = new HttpDelete(url);
            addAccessTokenToHeaders(delete, accessToken);
            ClassicHttpResponse response = client.execute(delete);
            logOutcome(delete, response);
            return response;
        }
    }

    public ClassicHttpResponse get(String uri,
                                   String accessToken) throws IOException {
        logger.info("GET {} at {}", uri, endpoint);
        String  url = endpoint + uri;
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            addAccessTokenToHeaders(get, accessToken);
            ClassicHttpResponse response = client.execute(get);
            logOutcome(get, response);
            return response;
        }
    }

    public ClassicHttpResponse put(String uri,
                                   String json,
                                   String accessToken) throws IOException {
        // Never log the JSON: it can carry secrets, eg an external catalog's client secret.
        logger.info("PUT {} at {}", uri, endpoint);
        String url = endpoint + uri;
        return post(json, new HttpPut(url), accessToken);
    }

    public ClassicHttpResponse post(String uri,
                                    String json,
                                    String accessToken) throws IOException {
        // Never log the JSON: it can carry secrets, eg an external catalog's client secret.
        logger.info("POST {} at {}", uri, endpoint);
        String url = endpoint + uri;
        return post(json, new HttpPost(url), accessToken);
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

    protected ClassicHttpResponse post(String payload,
                                       BasicClassicHttpRequest post) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            post.setEntity(new StringEntity(payload));
            ClassicHttpResponse response = client.execute(post);
            logOutcome(post, response);
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
