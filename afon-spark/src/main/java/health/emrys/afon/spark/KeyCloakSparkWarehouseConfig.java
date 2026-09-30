package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonUser;
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
 * <p>This overrides {@link #catalogConfigs(String, String)} to add the {@code oauth2-server-uri} pointing at
 * KeyCloak and to request the {@code openid} scope - KeyCloak rejects Polaris' "PRINCIPAL_ROLE:ALL"
 * scope (and Iceberg's default "catalog") with invalid_scope. The credentials are those of the given
 * {@link AfonUser}, which KeyCloak knows as a confidential client of the same name and secret.
 */
public class KeyCloakSparkWarehouseConfig extends SparkWarehouseConfig {

    private static final String KEYCLOAK_SCOPE = "openid";

    private static String kcUriFor(String kcHost) {
        return KeyCloakConfigurer.tokenEndpoint(kcHost, protocolFor(kcHost));
    }

    private final String oauth2ServerUri;

    KeyCloakSparkWarehouseConfig(AfonPropertyProvider properties, AfonUser user) {
        super(properties.getValue(POLARIS_ENDPOINT),
                protocolFor(properties.getValue(POLARIS_ENDPOINT)),
                user.username(),
                user.password(),
                new AfonCatalogs(properties)
        );
        this.oauth2ServerUri = kcUriFor(properties.getValue(OIDC_DOMAIN_NAME));
    }

    @Override
    protected Map<String, String> catalogConfigs(String sparkCatalog, String catalogName) {
        return addOauthServer(sparkCatalog, super.catalogConfigs(sparkCatalog, catalogName));
    }

    private @NonNull Map<String, String> addOauthServer(String sparkCatalog, Map<String, String> actual) {
        Map<String, String> conf    = new HashMap<>(actual);
        conf.put(format("spark.sql.catalog.%s.oauth2-server-uri", sparkCatalog), oauth2ServerUri);
        conf.put(format("spark.sql.catalog.%s.scope", sparkCatalog), KEYCLOAK_SCOPE);
        return Map.copyOf(conf);
    }
}
