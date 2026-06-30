package health.emrys.security.keycloak;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.KC_BOOTSTRAP_ADMIN_USERNAME;
import static health.emrys.afon.config.AfonProperties.KC_BOOTSTRAP_ADMIN_PASSWORD;

public class KeyCloakConfig {

    public static final String KEYCLOAK_IMAGE              = "quay.io/keycloak/keycloak:26.6.3";

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
