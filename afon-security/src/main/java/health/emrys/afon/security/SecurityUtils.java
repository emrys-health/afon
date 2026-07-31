package health.emrys.afon.security;

public class SecurityUtils {
    public static final String OAUTH_SCOPE = "PRINCIPAL_ROLE:ALL";

    public static String protocolFor(String url) {
        if (url.toLowerCase().contains("localhost")) {
            return "http";
        }
        return "https";
    }
}
