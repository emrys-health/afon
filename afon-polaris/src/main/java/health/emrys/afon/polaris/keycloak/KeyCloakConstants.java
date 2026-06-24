package health.emrys.afon.polaris.keycloak;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.String.format;

/**
 * Configuration shared between the KeyCloak container, the {@link KeyCloakAdmin} that provisions
 * users in it and the Polaris container that uses KeyCloak as its OIDC identity provider.
 */
public class KeyCloakConstants {

    public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.6.3";

    public static final int KEYCLOAK_PORT = 8080;

    /**
     * Network alias used by Polaris (running on the same docker network) to reach KeyCloak.
     */
    public static final String KEYCLOAK_ALIAS = "keycloak";

    /**
     * Single, pinned base URL used as the token issuer everywhere - token minting, introspection and
     * Polaris' JWKS validation - so the {@code iss} claim is always identical and never depends on the
     * request host. KeyCloak is pinned to this via {@code KC_HOSTNAME}. It is the docker network alias,
     * so host-side callers (e.g. {@link KeyCloakAdmin}) need {@code 127.0.0.1 keycloak} in /etc/hosts.
     */
    public static final String KEYCLOAK_BASE_URL = format("http://%s:%d", KEYCLOAK_ALIAS, KEYCLOAK_PORT);

    /**
     * Bootstrap admin of the KeyCloak instance (master realm).
     */
    public static final String KEYCLOAK_ADMIN          = "admin";
    public static final String KEYCLOAK_ADMIN_PASSWORD = "admin";

    /**
     * The realm, client and user that Polaris trusts. The user/password is the credential that a
     * Polaris client (Spark, Trino, the acceptance tests, ...) presents to KeyCloak to obtain a
     * token that Polaris will accept.
     */
    public static final String KEYCLOAK_REALM         = "polaris";

    /**
     * Base URL the host-side {@link KeyCloakAdmin} uses. Deliberately the same pinned host as the
     * issuer so everything lines up (requires {@code 127.0.0.1 keycloak} in /etc/hosts).
     */
    public static String hostBaseUrl() {
        return KEYCLOAK_BASE_URL;
    }

    /**
     * Issuer / discovery URL as seen from inside the docker network (i.e. by Polaris).
     */
    public static String internalRealmUrl(String host) {
        return format("http://%s:%d/realms/%s", host, KEYCLOAK_PORT, KEYCLOAK_REALM);
    }

    /**
     * Quarkus / Polaris configuration that switches Polaris from its internal authentication to
     * using KeyCloak as an external OIDC identity provider.
     *
     * <p>The keys are the canonical (dotted) Polaris/Quarkus property names; SmallRye Config reads
     * them from the environment, which is how the official Polaris docker images are configured.
     */
    public static Map<String, String> polarisOidcEnv(String keyCloakURL,
                                                     String user,
                                                     String userPassword) {
        Map<String, String> env = new LinkedHashMap<>();
        // Use KeyCloak instead of Polaris' internal token store.
        env.put("polaris.authentication.type", "external");
        env.put("polaris.authentication.authenticator.type", "default");
        // Map the token's username claim to a Polaris principal and its roles.
        env.put("polaris.oidc.principal-mapper.type", "default");
        env.put("polaris.oidc.principal-mapper.name-claim-path", "preferred_username");
        env.put("polaris.oidc.principal-roles-mapper.type", "default");
        // Point Quarkus OIDC at the KeyCloak realm. OIDC is disabled by default, so enable the tenant.
        env.put("quarkus.oidc.tenant-enabled", "true");
        env.put("quarkus.oidc.auth-server-url", keyCloakURL);
        env.put("quarkus.oidc.client-id", user);
        env.put("quarkus.oidc.credentials.secret", userPassword);
        env.put("quarkus.oidc.application-type", "service");
        return env;
    }
}