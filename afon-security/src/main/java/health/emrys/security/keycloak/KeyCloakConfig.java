package health.emrys.security.keycloak;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.KC_BOOTSTRAP_ADMIN_PASSWORD;
import static health.emrys.afon.config.AfonProperties.KC_BOOTSTRAP_ADMIN_USERNAME;
import static java.lang.String.format;

public class KeyCloakConfig {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(KeyCloakConfig.class);

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
        logger.info("host = {}, protocol = {}, uri = {}", host, protocol, uri);
        return uri;
    }

    public static @NonNull String urlFor(String host, String protocol) {
        String url;
        if ("HTTP".equalsIgnoreCase(protocol.toUpperCase())) {
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

    private final Properties properties;

    public KeyCloakConfig(Properties properties) {
        this.properties = properties;
    }

    public Map<String, String> configUsing() {
        Map<String, String> config = new HashMap<>();
        config.put(KC_BOOTSTRAP_ADMIN_USERNAME.getPropertyName(), KC_BOOTSTRAP_ADMIN_USERNAME.getValueFrom(properties));
        config.put(KC_BOOTSTRAP_ADMIN_PASSWORD.getPropertyName(), KC_BOOTSTRAP_ADMIN_PASSWORD.getValueFrom(properties));
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
