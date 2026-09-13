package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link SparkConnectClient} that authenticates catalog access with an OIDC bearer token
 * instead of Polaris client-credential (id/secret) basic auth.
 *
 * On construction, it exchanges the base client's Polaris client id/secret for a user token via
 * {@link KeyCloakProvider#userToken}. Each warehouse config built by
 * {@link #warehouseConfig(String, String)} then has that basic-auth credential stripped out and
 * replaced with a {@code spark.sql.catalog.<warehouse>.token} entry carrying the OIDC token, so
 * the resulting {@link org.apache.spark.sql.SparkSession} authenticates to Polaris as that OIDC
 * user rather than as the client application.
 */
public class SparkConnectUsingOIDC extends SparkConnectClient {

    private final String token;

    public SparkConnectUsingOIDC(AfonPropertyProvider properties) throws IOException {
        super(properties);
        token = new KeyCloakProvider(properties).userToken(getClientId(), getClientSecret());
    }

    protected Map<String, String> warehouseConfig(String warehouseName, String catalogName) {
        Map<String, String> defaults = super.warehouseConfig(warehouseName, catalogName);
        Map<String, String> config = new HashMap<>();
        defaults.entrySet().stream()
                .filter(x -> !x.getValue().contains(getClientId() + ":" + getClientSecret()))
                .forEach(x -> config.put(x.getKey(), x.getValue()));

        config.put(String.format("spark.sql.catalog.%s.token", warehouseName), token);
        return config;
    }
}
