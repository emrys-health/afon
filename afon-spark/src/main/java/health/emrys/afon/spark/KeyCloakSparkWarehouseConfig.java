package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.protocolFor;
import static java.lang.String.format;

/**
 * A {@link SparkWarehouseConfig} for a Polaris running in external-auth mode (KeyCloak as the OIDC
 * identity provider). In that mode Polaris no longer implements its own token endpoint
 * ({@code /api/catalog/v1/oauth/tokens} returns HTTP 501), so the Iceberg REST catalog must obtain
 * its OAuth2 token directly from KeyCloak.
 *
 * <p>This overrides {@link #sparkConfigs(String)} to add the {@code oauth2-server-uri} pointing at
 * KeyCloak and to request the {@code openid} scope - KeyCloak rejects Polaris' "PRINCIPAL_ROLE:ALL"
 * scope (and Iceberg's default "catalog") with invalid_scope. The credentials passed to the
 * superclass must identify a client KeyCloak knows about.
 */
public class KeyCloakSparkWarehouseConfig extends SparkWarehouseConfig {

    private static final String KEYCLOAK_SCOPE = "openid";

    private static String kcUriFor(String kcHost) {
        return KeyCloakConfigurer.tokenEndpoint(kcHost, protocolFor(kcHost));
    }

    private final String oauth2ServerUri;

    KeyCloakSparkWarehouseConfig(AfonPropertyProvider properties) {
        super(properties.getValue(USER_POLARIS_ID),
                properties.getValue(USER_POLARIS_SECRET),
                properties.getValue(POLARIS_ENDPOINT),
                protocolFor(properties.getValue(POLARIS_ENDPOINT)),
                new AfonCatalogs(properties)
        );
        this.oauth2ServerUri = kcUriFor(properties.getValue(KC_HOSTNAME));
    }

    @Override
    public Map<String, String> sparkConfigs(String sparkCatalog, String catalogName) {
        return addOauthServer(sparkCatalog, super.sparkConfigs(sparkCatalog, catalogName));
    }

    @Override
    public Map<String, String> sparkConfigs(String sparkCatalog) {
        return addOauthServer(sparkCatalog, super.sparkConfigs(sparkCatalog));
    }

    private @NonNull Map<String, String> addOauthServer(String sparkCatalog, Map<String, String> actual) {
        Map<String, String> conf    = new HashMap<>(actual);
        conf.put(format("spark.sql.catalog.%s.oauth2-server-uri", sparkCatalog), oauth2ServerUri);
        conf.put(format("spark.sql.catalog.%s.scope", sparkCatalog), KEYCLOAK_SCOPE);
        return Map.copyOf(conf);
    }
}
