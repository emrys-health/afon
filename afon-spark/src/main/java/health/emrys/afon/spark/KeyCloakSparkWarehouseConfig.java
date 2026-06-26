package health.emrys.afon.spark;

import java.util.HashMap;
import java.util.Map;

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

    private final String oauth2ServerUri;

    public KeyCloakSparkWarehouseConfig(
        String clientId,
        String clientSecret,
        String host,
        String polarisProtocol,
        String oauth2ServerUri) {
        super(clientId, clientSecret, host, polarisProtocol);
        this.oauth2ServerUri = oauth2ServerUri;
    }

    @Override
    public Map<String, String> sparkConfigs(String sparkCatalog) {
        Map<String, String> conf = new HashMap<>(super.sparkConfigs(sparkCatalog));
        conf.put(format("spark.sql.catalog.%s.oauth2-server-uri", sparkCatalog), oauth2ServerUri);
        conf.put(format("spark.sql.catalog.%s.scope", sparkCatalog), KEYCLOAK_SCOPE);
        return Map.copyOf(conf);
    }
}