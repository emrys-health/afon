package health.emrys.afon.polaris.keycloak;

import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration shared between the KeyCloak container, the {@link KeyCloakProvider} that provisions
 * users in it and the Polaris container that uses KeyCloak as its OIDC identity provider.
 */
public class KeyCloakHelper {

    /**
     * Quarkus / Polaris configuration that switches Polaris from its internal authentication to
     * using KeyCloak as an external OIDC identity provider.
     *
     * <p>The keys are the canonical (dotted) Polaris/Quarkus property names; SmallRye Config reads
     * them from the environment, which is how the official Polaris docker images are configured.
     */
    public static Map<String, String> polarisOidcEnv(String authServerUrl,
                                                     String clientId,
                                                     String clientSecret) {
        Map<String, String> env = new LinkedHashMap<>();
        // Use KeyCloak instead of Polaris' internal token store.
        env.put("polaris.authentication.type", "external");
        env.put("polaris.authentication.authenticator.type", "default");
        // Map the token to a Polaris principal by a dedicated 'polaris_principal' claim that KeyCloak
        // protocol mappers stamp onto every token (see KeyCloakProvider):
        //   - human browser logins   -> polaris_principal = the user's username (e.g. "will")
        //   - client_credentials     -> polaris_principal = the bare clientId (e.g. "will" / "root")
        // and each user/service is provisioned a Polaris principal of that same name (UserOnboarderMain,
        // polaris.bootstrap.credentials). A dedicated claim is used rather than 'preferred_username'
        // because that built-in claim differs by grant type — a client_credentials grant yields
        // "service-account-<clientId>" — and a mapper cannot reliably override the built-in field
        // (KeyCloak serialises the built-in value over the mapped one). 'polaris_principal' has no
        // built-in mapper to collide with, so both grant types resolve to the intended principal.
        env.put("polaris.oidc.principal-mapper.type", "default");
        env.put("polaris.oidc.principal-mapper.name-claim-path", "polaris_principal");
        env.put("polaris.oidc.principal-roles-mapper.type", "default");
        // Point Quarkus OIDC at the KeyCloak realm. OIDC is disabled by default, so enable the tenant.
        env.put("quarkus.oidc.tenant-enabled", "true");
        env.put("quarkus.oidc.auth-server-url", authServerUrl);
        env.put("quarkus.oidc.client-id", clientId);
        env.put("quarkus.oidc.credentials.secret", clientSecret);
        env.put("quarkus.oidc.application-type", "service");
        // TESTING RELAXATION: accept any issuer. KeyCloak (KC_HOSTNAME_STRICT=false) stamps 'iss' from
        // the request host, so a token minted via 'localhost' differs from Polaris' 'keycloak' issuer.
        // The token signature is still verified against the realm JWKS - only the issuer-host check is
        // skipped. Do NOT use this in production; there, pin a single issuer via KC_HOSTNAME instead.
        env.put("quarkus.oidc.token.issuer", "any");
        return env;
    }

    /**
     * What the Polaris of a Data Provider requires of a token, in place of accepting any that its Keycloak signed.
     *
     * @param issuer       the issuer of the central identity, pinned: a token whose {@code iss} is another is refused
     * @param audience     the audience that is the Data Provider's own, which the tokens of the TREs and of its admins carry: one without it is refused, so a
     *                     token meant for another Data Provider, or for the TRE itself, cannot be replayed here
     * @param acr          the {@code acr} that proves a second factor, eg {@code mfa}: a token of a password alone is refused
     * @param projectClaim the claim that names the project, a list: it selects which of the principal's roles are active, only the one of the project the
     *                     token is for, {@code <id>Project}, so that a member of two projects reads in each only what that project may
     */
    public record Trust(String issuer, String audience, String acr, String projectClaim) {
    }

    /**
     * As {@link #polarisOidcEnv(String, String, String)}, with the trust of a Data Provider: the issuer is pinned, in place of {@code any}, the audience
     * and the {@code acr} are required, and the project claim chooses the active role.  The names are written as an environment variable takes
     * them, which the stack turns into the name of the variable that sets each: a quoted key of a map, as the claim {@code acr} is, and an index, as
     * the mapping of roles has.
     */
    public static Map<String, String> polarisOidcEnv(String authServerUrl, String clientId, String clientSecret, Trust trust) {
        Map<String, String> env = polarisOidcEnv(authServerUrl, clientId, clientSecret);
        env.put("quarkus.oidc.token.issuer", trust.issuer());
        env.put("quarkus.oidc.token.audience", trust.audience());
        env.put("quarkus.oidc.token.required-claims.\"acr\"", trust.acr());
        // the roles of the token are the project that it names, and they become the principal role of that project and no other: a role that the token
        // does not name is not active, so that the roles that a member has for other projects do not give a token of this one their access
        env.put("quarkus.oidc.roles.role-claim-path", trust.projectClaim());
        env.put("polaris.oidc.principal-roles-mapper.filter", ".+");
        env.put("polaris.oidc.principal-roles-mapper.mappings[0].regex", "(.+)");
        env.put("polaris.oidc.principal-roles-mapper.mappings[0].replacement", "PRINCIPAL_ROLE:$1Project");
        return env;
    }
}