package health.emrys.afon.polaris.comms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.client5.http.classic.methods.HttpGet;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static java.lang.String.format;

/**
 * Closes the HTTP connection on each call.
 * So, not efficient but it won't leak resources.
 * Since you won't make many calls, this is reasonable,
 */
public class AfonPolarisClient {

    static final Logger logger       = org.slf4j.LoggerFactory.getLogger(AfonPolarisClient.class);
    static final String CONTENT_TYPE = "Content-Type";

    private final String endpoint;
    private final String clientId;
    private final String clientSecret;

    public AfonPolarisClient(Properties properties) {
        this(format("http://%s", POLARIS_ENDPOINT.getValueFrom(properties)),
             POLARIS_ID.getValueFrom(properties),
             POLARIS_SECRET.getValueFrom(properties));
    }

    public AfonPolarisClient(String endpoint, String clientId, String clientSecret) {
        this.endpoint     = endpoint;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
    }

    public String getAccessToken() throws RuntimeException {
        String uri = format("%s/api/catalog/v1/oauth/tokens", endpoint);
        logger.info("Calling " + uri);
        HttpPost post = new HttpPost(uri);
        post.setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");
        post.setHeader("Polaris-Realm", "POLARIS");
        String body = format("grant_type=client_credentials&client_id=%s&client_secret=%s&scope=PRINCIPAL_ROLE:ALL", clientId, clientSecret);
        try {
            ClassicHttpResponse response = makeCall(body, post);
            return parseAccessToken(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getEndpoint() {
        return endpoint;
    }

    public ClassicHttpResponse putJsonCall(String uri,
                                           String json,
                                           String accessToken) throws IOException {
        return makeCallWithToken(json, new HttpPut(endpoint + uri), accessToken);
    }

    public ClassicHttpResponse postJsonCall(String uri,
                                            String json,
                                            String accessToken) throws IOException {
        String url = endpoint + uri;
        logger.info("Calling " + url);
        return makeCallWithToken(json, new HttpPost(url), accessToken);
    }

    public ClassicHttpResponse getCall(String uri,
                                       String accessToken) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(endpoint + uri);
            request.setHeader("Authorization", "Bearer " + accessToken);
            ClassicHttpResponse response = client.execute(request);
            logger.info(format("%s:%d", request, response.getCode()));
            return response;
        }
    }

    private ClassicHttpResponse makeCallWithToken(String payload,
                                                  BasicClassicHttpRequest post,
                                                  String accessToken) throws IOException {
        post.setHeader("Authorization", "Bearer " + accessToken);
        post.setHeader("Accept", "application/json");
        post.setHeader(CONTENT_TYPE, "application/json");
        return makeCall(payload, post);
    }

    private static String parseAccessToken(ClassicHttpResponse response) throws IOException {
        ObjectMapper        mapper                  = new ObjectMapper();
        Map<String, Object> tokenResponse           = mapper.readValue(response.getEntity().getContent(), Map.class);
        String              accessTokenFromResponse = (String) tokenResponse.get("access_token");
        return accessTokenFromResponse;
    }

    private ClassicHttpResponse makeCall(String payload,
                                         BasicClassicHttpRequest post) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            post.setEntity(new StringEntity(payload));
            ClassicHttpResponse response = (ClassicHttpResponse) client.execute(post);
            logger.info(format("%s:%d", post, response.getCode()));
            assert response.getCode() == 200;
            return response;
        }
    }
}
