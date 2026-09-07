package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.OAuthProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SparkConnectUsingOIDC extends SparkConnect {

    private final OAuthProvider provider;
    private final String token;

    public SparkConnectUsingOIDC(AfonPropertyProvider properties) throws IOException {
        super(properties);
        provider = new KeyCloakProvider(properties);
        token = provider.userToken(getSparkUser(), getSparkPassword());
    }

    protected Map<String, String> getWarehouseConfig(String warehouseName,
                                                     String catalogName
    ) {
        Map<String, String>               defaults        = super.getWarehouseConfig(warehouseName, catalogName);
        Map<String, String>               warehouseConfig = new HashMap<>();
        defaults
            .entrySet().stream()
            .filter(x -> !x.getValue().contains(getSparkUser() + ":" + getSparkPassword()))
            .forEach(x -> warehouseConfig.put(x.getKey(), x.getValue()));

        warehouseConfig.put(String.format("spark.sql.catalog.%s.token", warehouseName), token);
        return warehouseConfig;
    }
}
