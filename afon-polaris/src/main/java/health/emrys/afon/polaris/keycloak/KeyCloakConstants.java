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
    public static final String KEYCLOAK_CLIENT_ID     = "polaris";
    public static final String KEYCLOAK_CLIENT_SECRET = "polaris-secret";
    public static final String KEYCLOAK_USER          = "polaris-user";
    public static final String KEYCLOAK_USER_PASSWORD = "polaris-password";

    /**
     * Base URL of KeyCloak as seen from the host (the {@link KeyCloakAdmin} runs on the host JVM).
     */
    public static String hostBaseUrl() {
        return format("http://localhost:%d", KEYCLOAK_PORT);
    }

    /**
     * Issuer / discovery URL as seen from inside the docker network (i.e. by Polaris).
     */
    public static String internalRealmUrl() {
        return format("http://%s:%d/realms/%s", KEYCLOAK_ALIAS, KEYCLOAK_PORT, KEYCLOAK_REALM);
    }

    /**
     * Quarkus / Polaris configuration that switches Polaris from its internal authentication to
     * using KeyCloak as an external OIDC identity provider.
     *
     * <p>The keys are the canonical (dotted) Polaris/Quarkus property names; SmallRye Config reads
     * them from the environment, which is how the official Polaris docker images are configured.
     */
    public static Map<String, String> polarisOidcEnv() {
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
        env.put("quarkus.oidc.auth-server-url", internalRealmUrl());
        env.put("quarkus.oidc.client-id", KEYCLOAK_CLIENT_ID);
        env.put("quarkus.oidc.credentials.secret", KEYCLOAK_CLIENT_SECRET);
        env.put("quarkus.oidc.application-type", "service");
        return env;
    }
}