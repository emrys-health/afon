package health.emrys.afon.polaris.config;

import health.emrys.afon.rest.HttpResponses;
import health.emrys.afon.rest.RestClient;
import org.apache.hc.core5.http.message.BasicHeader;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;

/**
 *  A {@link RestClient} on Polaris URIs, which also obtains the access token to present to Polaris.
 */
public class PolarisRestClient extends RestClient {

    static final Logger logger       = org.slf4j.LoggerFactory.getLogger(PolarisRestClient.class);
    static final String TOKENS_PATH  = "/api/catalog/v1/oauth/tokens";

    protected final String clientId;
    protected final String clientSecret;

    public PolarisRestClient(String endpoint, String clientId, String clientSecret) {
        super(endpoint);
        this.clientId     = clientId;
        this.clientSecret = ***REMOVED***
    }

    public static @NonNull String tokensURI(String endpoint) {
        return endpoint + TOKENS_PATH;
    }

    /**
     * Odd. This con sometimes receive a HTTP 503 even though the Polaris server logs HTTP 200.
     * Hence, I/O and server (5xx) errors are retried, while other failures (e.g. 401) fail immediately.
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
                        logger.info("Sleeping {}s before retry...",  millis/1000);
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
        logger.info("Access token from {}", tokensURI(getEndpoint()));
        return postForm(TOKENS_PATH, Map.of(
            "grant_type",    "client_credentials",
            "client_id",     clientId,
            "client_secret", clientSecret,
            "scope",         "PRINCIPAL_ROLE:ALL"
        ), HttpResponses::parseAccessToken, new BasicHeader("Polaris-Realm", "POLARIS"));
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getEndpoint() {
        return getBaseUrl();
    }

}
