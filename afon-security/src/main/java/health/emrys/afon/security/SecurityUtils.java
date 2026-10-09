package health.emrys.afon.security;

public class SecurityUtils {
    /**
     * The scope Polaris itself grants, for the clients getting their token from its own token endpoint.
     */
    public static final String OAUTH_SCOPE = "PRINCIPAL_ROLE:ALL";

    /**
     * The scope to request from KeyCloak, from which Polaris, in external-auth mode, expects its clients to get their token:
     * KeyCloak rejects {@link #OAUTH_SCOPE} with {@code invalid_scope}, and Polaris answers HTTP 501 to its own token endpoint.
     */
    public static final String KEYCLOAK_SCOPE = "openid";

    public static String protocolFor(String url) {
        if (url.toLowerCase().contains("localhost")) {
            return "http";
        }
        return "https";
    }
}
