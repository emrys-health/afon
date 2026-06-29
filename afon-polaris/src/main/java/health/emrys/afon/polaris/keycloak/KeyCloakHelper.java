package health.emrys.afon.polaris.keycloak;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.String.format;

/**
 * Configuration shared between the KeyCloak container, the {@link KeyCloakAdmin} that provisions
 * users in it and the Polaris container that uses KeyCloak as its OIDC identity provider.
 */
public class KeyCloakHelper {

    public static final int KEYCLOAK_PORT = 8080;

    /**
     * The realm, client and user that Polaris trusts. The user/password is the credential that a
     * Polaris client (Spark, Trino, the acceptance tests, ...) presents to KeyCloak to obtain a
     * token that Polaris will accept.
     */
    public static final String KEYCLOAK_REALM         = "polaris";

    /**
     * Issuer / discovery URL as seen from inside the docker network (i.e. by Polaris).
     */
    public static String internalRealmUrl(String host) {
        return format("http://%s:%d/realms/%s", host, KEYCLOAK_PORT, KEYCLOAK_REALM);
    }

    /**
     * The OIDC token endpoint for the Polaris realm on the given host. This is where OAuth2 clients
     * (the acceptance-test Polaris client, and the Spark/Iceberg REST catalog) obtain their tokens in
     * external-auth mode - Polaris itself returns HTTP 501 from {@code /api/catalog/v1/oauth/tokens}.
     */
    public static String tokenEndpoint(String host) {
        return internalRealmUrl(host) + "/protocol/openid-connect/token";
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
        // Map the token's client identity to a Polaris principal and its roles. We use the 'azp'
        // (authorized party) claim rather than 'preferred_username': for a client_credentials grant
        // KeyCloak sets preferred_username to "service-account-<clientId>" (e.g. "service-account-root"),
        // which does not match the bootstrapped Polaris principal. 'azp' is the bare client id ("root"),
        // which is the principal Polaris was bootstrapped with (see polaris.bootstrap.credentials).
        env.put("polaris.oidc.principal-mapper.type", "default");
        env.put("polaris.oidc.principal-mapper.name-claim-path", "azp");
        env.put("polaris.oidc.principal-roles-mapper.type", "default");
        // Point Quarkus OIDC at the KeyCloak realm. OIDC is disabled by default, so enable the tenant.
        env.put("quarkus.oidc.tenant-enabled", "true");
        env.put("quarkus.oidc.auth-server-url", keyCloakURL);
        env.put("quarkus.oidc.client-id", user);
        env.put("quarkus.oidc.credentials.secret", userPassword);
        env.put("quarkus.oidc.application-type", "service");
        // TESTING RELAXATION: accept any issuer. KeyCloak (KC_HOSTNAME_STRICT=false) stamps 'iss' from
        // the request host, so a token minted via 'localhost' differs from Polaris' 'keycloak' issuer.
        // The token signature is still verified against the realm JWKS - only the issuer-host check is
        // skipped. Do NOT use this in production; there, pin a single issuer via KC_HOSTNAME instead.
        env.put("quarkus.oidc.token.issuer", "any");
        return env;
    }
}