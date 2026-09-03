package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.protocolFor;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.tokenEndpoint;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * An {@link PolarisRestClient} for a Polaris configured to use KeyCloak as an external OIDC identity
 * provider. In that mode Polaris no longer implements its own token endpoint
 * ({@code /api/catalog/v1/oauth/tokens} returns HTTP 501), so the access token is obtained directly
 * from KeyCloak. The inherited catalog/management calls then present it to Polaris as a bearer token.
 *
 * Get the token with:
 * <pre>
 curl -s -X POST {@link AfonProperty#POLARIS_ENDPOINT}/realms/polaris/protocol/openid-connect/token -H "Content-Type: application/x-www-form-urlencoded" -d grant_type=client_credentials -d client_id=<polaris_id> -d client_secret=<polaris_secret>
 * </pre>
 *
 * Validate with:
 * <pre>
 curl -s -X POST {@link AfonProperty#POLARIS_ENDPOINT}/realms/polaris/protocol/openid-connect/token/introspect -H "Content-Type: application/x-www-form-urlencoded" -d token=<polaris_token>  -d client_id=<polaris_id> -d client_secret=<polaris_secret> | jq
 * </pre>
 */
public class KeyCloakPolarisRestClient extends PolarisRestClient {

    private static final Logger logger = getLogger(KeyCloakPolarisRestClient.class);

    private static @NonNull String tokenEndpointFor(AfonPropertyProvider properties) {
        return tokenEndpointFor(properties.getValue(KC_HOSTNAME));
    }

    private static @NonNull String tokenEndpointFor(String kcHostname) {
        return tokenEndpoint(kcHostname, protocolFor(kcHostname));
    }

    private final String keyCloakTokenUri;

    public KeyCloakPolarisRestClient(AfonPropertyProvider properties) {
        this(properties, protocolFor(properties.getValue(POLARIS_ENDPOINT)));
    }

    public KeyCloakPolarisRestClient(AfonPropertyProvider properties, String protocol) {
        super(format("%s://%s", protocol, properties.getValue(POLARIS_ENDPOINT)),
                properties.getValue(POLARIS_ID),
                properties.getValue(POLARIS_SECRET));
        this.keyCloakTokenUri = tokenEndpointFor(properties);
    }

    @Override
    public String accessToken() throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        String   url  = urlEncode(form);

        HttpPost post = new HttpPost(keyCloakTokenUri);
        post.setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");
        logger.info("Requesting KeyCloak access token from {} as client {}", keyCloakTokenUri, clientId);
        return parseAccessToken(post(url, post));
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
