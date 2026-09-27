package health.emrys.afon.rest;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.BufferedHttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static health.emrys.afon.rest.HttpResponses.logOutcome;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * A client implementing the REST commands (DELETE, GET, PUT, POST) on the paths of a base URL, with an
 * optional bearer access token.
 *
 * <p>Request bodies are never logged: they can carry secrets, eg a client secret or a user's password.
 *
 * <p>The methods returning a {@link ClassicHttpResponse} return one detached from the connection, with its
 * body buffered, so that it can be read after the request has completed. The methods taking a
 * {@link HttpClientResponseHandler} instead handle the response while the connection is still open.
 */
public class RestClient {

    private static final Logger logger = getLogger(RestClient.class);

    public static final String AUTHORIZATION = "Authorization";
    public static final String ACCEPT        = "Accept";
    public static final String CONTENT_TYPE  = "Content-Type";

    private final String baseUrl;

    public RestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ClassicHttpResponse delete(String path, String accessToken) throws IOException {
        return execute(new HttpDelete(url(path)), accessToken);
    }

    public ClassicHttpResponse get(String path, String accessToken) throws IOException {
        return execute(new HttpGet(url(path)), accessToken);
    }

    public <T> T get(String path, String accessToken, HttpClientResponseHandler<T> handler) throws IOException {
        return execute(new HttpGet(url(path)), accessToken, handler);
    }

    public ClassicHttpResponse put(String path, String json, String accessToken) throws IOException {
        return execute(withJson(new HttpPut(url(path)), json), accessToken);
    }

    public <T> T put(String path, String json, String accessToken, HttpClientResponseHandler<T> handler) throws IOException {
        return execute(withJson(new HttpPut(url(path)), json), accessToken, handler);
    }

    public ClassicHttpResponse post(String path, String json, String accessToken) throws IOException {
        return execute(withJson(new HttpPost(url(path)), json), accessToken);
    }

    public <T> T post(String path, String json, String accessToken, HttpClientResponseHandler<T> handler) throws IOException {
        return execute(withJson(new HttpPost(url(path)), json), accessToken, handler);
    }

    /**
     * POSTs a url-encoded form, eg to an OAuth token endpoint, logging its outcome.
     */
    public <T> T postForm(String path,
                          Map<String, String> form,
                          HttpClientResponseHandler<T> handler,
                          Header... headers) throws IOException {
        HttpPost post = new HttpPost(url(path));
        post.setHeaders(headers);
        post.setEntity(new StringEntity(form(form), ContentType.APPLICATION_FORM_URLENCODED));
        return execute(post, null, response -> {
            logOutcome(post, response);
            return handler.handleResponse(response);
        });
    }

    /**
     * Executes the request, logging its outcome, and returns the response detached from the connection.
     */
    protected ClassicHttpResponse execute(ClassicHttpRequest request, String accessToken) throws IOException {
        return execute(request, accessToken, response -> {
            ClassicHttpResponse detached = detached(response);
            logOutcome(request, detached);
            return detached;
        });
    }

    protected <T> T execute(ClassicHttpRequest request,
                            String accessToken,
                            HttpClientResponseHandler<T> handler) throws IOException {
        // Never log the body: it can carry secrets.
        logger.info("{} {} at {}", request.getMethod(), request.getRequestUri(), baseUrl);
        if (accessToken != null) {
            request.setHeader(AUTHORIZATION, "Bearer " + accessToken);
        }
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(request, handler);
        }
    }

    private String url(String path) {
        return baseUrl + path;
    }

    private static ClassicHttpRequest withJson(ClassicHttpRequest request, String json) {
        request.setHeader(ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        request.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        return request;
    }

    /**
     * A copy of the response, with its body buffered, which remains readable once the connection is released.
     */
    private static ClassicHttpResponse detached(ClassicHttpResponse response) throws IOException {
        BasicClassicHttpResponse detached = new BasicClassicHttpResponse(response.getCode(), response.getReasonPhrase());
        detached.setVersion(response.getVersion());
        detached.setHeaders(response.getHeaders());
        HttpEntity entity = response.getEntity();
        if (entity != null) {
            detached.setEntity(new BufferedHttpEntity(entity));
        }
        return detached;
    }

    /**
     * The url-encoded form of the parameters.
     */
    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
