package health.emrys.afon.security.keycloak;

import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.KC_BOOTSTRAP_ADMIN_PASSWORD;
import static health.emrys.afon.config.AfonProperty.KC_BOOTSTRAP_ADMIN_USERNAME;
import static java.lang.String.format;

public class KeyCloakConfigurer {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(KeyCloakConfigurer.class);

    public static final String HTTP = "http";
    public static final int    KEYCLOAK_PORT  = 8080;
    public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.6.3";

    /**
     * The realm, client and user that Polaris trusts. The user/password is the credential that a
     * Polaris client (Spark, Trino, the acceptance tests, ...) presents to KeyCloak to obtain a
     * token that Polaris will accept.
     */
    public static final String KEYCLOAK_REALM = "polaris";

    /**
     * Issuer / discovery URL as seen from inside the docker network (i.e. by Polaris).
     */
    public static String internalRealmUrl(String host, String protocol) {
        String uri = format("%s/realms/%s", urlFor(host, protocol), KEYCLOAK_REALM);
        logger.info("KeyCloak host = {}, protocol = {}, uri = {}", host, protocol, uri);
        return uri;
    }

    public static @NonNull String urlFor(String host, String protocol) {
        String url;
        if (host.toLowerCase().startsWith(HTTP)) {
            url = host;
        } else if (HTTP.equalsIgnoreCase(protocol.toLowerCase())) {
            url = format(
                    "%s://%s:%d",
                    protocol,
                    host,
                    KEYCLOAK_PORT);
        } else {
            url = format(
                    "%s://%s",
                    protocol,
                    host);
        }
        return url;
    }

    /**
     * The OIDC token endpoint for the Polaris realm on the given host. This is where OAuth2 clients
     * (the acceptance-test Polaris client, and the Spark/Iceberg REST catalog) obtain their tokens in
     * external-auth mode - Polaris itself returns HTTP 501 from {@code /api/catalog/v1/oauth/tokens}.
     */
    public static String tokenEndpoint(String host, String protocol) {
        return internalRealmUrl(host, protocol) + "/protocol/openid-connect/token";
    }

    private final AfonPropertyProvider properties;

    public KeyCloakConfigurer(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public Map<String, String> config() {
        Map<String, String> config = new HashMap<>();
        config.put(KC_BOOTSTRAP_ADMIN_USERNAME.getName(), properties.getValue(KC_BOOTSTRAP_ADMIN_USERNAME));
        config.put(KC_BOOTSTRAP_ADMIN_PASSWORD.getName(), properties.getValue(KC_BOOTSTRAP_ADMIN_PASSWORD));
        config.put("KC_HTTP_ENABLED", "true");
        // Run KeyCloak at DEBUG level to aid diagnosing OIDC/token issues.
        config.put("KC_LOG_LEVEL", "DEBUG");
        // Issuer is left dynamic (stamped from the request host) so host-side callers can use
        // 'localhost' with no /etc/hosts entry. Polaris tolerates the resulting issuer-host
        // difference via quarkus.oidc.token.issuer=any (see KeyCloakHelper#polarisOidcEnv);
        // the token signature is still validated. For production, pin KC_HOSTNAME instead.
        config.put("KC_HOSTNAME_STRICT", "false");
        return config;
    }

}
