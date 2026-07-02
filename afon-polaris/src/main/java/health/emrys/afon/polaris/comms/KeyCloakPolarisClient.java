package health.emrys.afon.polaris.comms;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.security.keycloak.KeyCloakConfig.tokenEndpoint;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * An {@link AfonPolarisClient} for a Polaris configured to use KeyCloak as an external OIDC identity
 * provider. In that mode Polaris no longer implements its own token endpoint
 * ({@code /api/catalog/v1/oauth/tokens} returns HTTP 501), so the access token is obtained directly
 * from KeyCloak. The inherited catalog/management calls then present it to Polaris as a bearer token.
 */
public class KeyCloakPolarisClient extends AfonPolarisClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger       log    = getLogger(KeyCloakPolarisClient.class);

    public static void main(String[] args) {
        KeyCloakPolarisClient app = new KeyCloakPolarisClient(new AfonPropertiesLoader().getProperties());
        log.info("access token: {}", app.getAccessToken());
    }

    private final String keyCloakTokenUri;

    public KeyCloakPolarisClient(Properties properties) {
        // The Polaris endpoint (used for the bearer-authenticated catalog/management calls).
        super(properties);
        // Use the same KeyCloak host that Polaris discovered, so the token's issuer matches and
        // Polaris accepts the bearer token.
        String kcHostname = KC_HOSTNAME.getValueFrom(properties);
        this.keyCloakTokenUri = tokenEndpoint(kcHostname, protocolFor(kcHostname));
    }

    public KeyCloakPolarisClient(Properties properties, String kcHostname) {
        // The Polaris endpoint (used for the bearer-authenticated catalog/management calls).
        super(properties);
        // Use the same KeyCloak host that Polaris discovered, so the token's issuer matches and
        // Polaris accepts the bearer token.
        this.keyCloakTokenUri = tokenEndpoint(kcHostname, protocolFor(kcHostname));
    }

    private String protocolFor(String kcHostname) {
        if (kcHostname.toLowerCase().contains("localhost")) {
            return "http";
        }
        return "https";
    }

    /**
     * Get the token with:
     * <pre>
curl -s -X POST http://localhost:8080/realms/polaris/protocol/openid-connect/token -H "Content-Type: application/x-www-form-urlencoded" -d grant_type=client_credentials -d client_id=root -d client_secret=s3cr3t
     * </pre>
     *
     * Validate with:
     *
     * <pre>
curl -s -X POST http://localhost:8080/realms/polaris/protocol/openid-connect/token/introspect -H "Content-Type: application/x-www-form-urlencoded" -d token=$POLARIS_TOKEN  -d client_id=root -d client_secret=s3cr3t | jq
     * </pre>
     */
    @Override
    public String callAccessToken() throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);

        HttpPost post = new HttpPost(keyCloakTokenUri);
        String   url  = urlEncode(form);
        post.setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");
        logger.info("Requesting KeyCloak access token from {}", keyCloakTokenUri);
        ClassicHttpResponse response = makeCall(url, post);
        return parseAccessToken(response);
    }

    private static String urlEncode(Map<String, String> params) {
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
