package health.emrys.afon.security;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.BufferedHttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

/**
 * Helpers for HTTP responses, shared by the identity provider and Polaris clients: checking and logging
 * outcomes, and the one place where an access token is parsed from a token endpoint response.
 */
public final class HttpResponses {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponses.class);

    public static final String EMPTY = "<empty>";

    private static final ObjectMapper MAPPER =
        JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    private HttpResponses() {
    }

    public static boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

    public static void logIfFailure(HttpResponse response, String description) {
        if (isFailure(response)) {
            logger.error("{}, code: {}, error: {}", description,
                response == null ? null : response.getCode(), response == null ? null : response.getReasonPhrase());
        }
    }

    /**
     * The bodies of the failed responses.
     */
    public static List<String> failuresOf(List<HttpResponse> responses) {
        return responses.stream()
            .filter(HttpResponses::isFailure)
            .map(HttpResponses::contentOf)
            .toList();
    }

    /**
     * Logs the request (method and URI, never its body) and its response code, with the response body
     * when it failed.
     */
    public static void logOutcome(HttpRequest request, HttpResponse response) {
        if (isFailure(response)) {
            logger.warn("Request: {}, code: {}, response: {}", request,
                response == null ? null : response.getCode(), contentOf(response));
        } else {
            logger.info("Request: {}, code: {}", request, response.getCode());
        }
    }

    /**
     * Parses the access token from a token endpoint response.
     *
     * @throws IOException           if the response is a server error (5xx), which may be transient
     *                               and so may be retried (eg by {@code PolarisRestClient#getAccessToken()})
     * @throws IllegalStateException if the response is any other failure (e.g. 401 on bad credentials)
     *                               or carries no access token, which retrying will not fix
     */
    public static String parseAccessToken(ClassicHttpResponse response) throws IOException {
        if (isFailure(response)) {
            String message = format("Failed to obtain access token, code: %s, response: %s",
                response == null ? null : response.getCode(), contentOf(response));
            if (response == null || response.getCode() / 100 == 5) {
                throw new IOException(message);
            }
            throw new IllegalStateException(message);
        }
        Map<String, Object> tokenResponse = MAPPER.readValue(response.getEntity().getContent(), Map.class);
        String accessToken = (String) tokenResponse.get("access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(format("No access token in response, code: %s, response: %s",
                    response.getCode(), tokenResponse));
        }
        return accessToken;
    }

    /**
     * The body of the response, or {@link #EMPTY}. The entity is buffered, so that reading it here doesn't
     * drain the one-shot stream out from under callers that still need to read the body afterwards.
     */
    public static @NonNull String contentOf(HttpResponse response) {
        if (response instanceof ClassicHttpResponse classicResponse) {
            try {
                HttpEntity entity = classicResponse.getEntity();
                if (entity != null) {
                    BufferedHttpEntity buffered = new BufferedHttpEntity(entity);
                    classicResponse.setEntity(buffered);
                    return EntityUtils.toString(buffered);
                }
            } catch (Exception e) {
                logger.error("Failed to read response body", e);
            }
        }
        return EMPTY;
    }
}
