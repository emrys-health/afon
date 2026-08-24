package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class KeyCloakSparkConnectUsingOIDC extends KeyCloakSparkConnect {

    private final String keyCloakToken;

    public KeyCloakSparkConnectUsingOIDC(AfonPropertyProvider properties) throws IOException {
        super(properties);
        keyCloakToken = new KeyCloakProvider(properties).userToken(getSparkUser(), getSparkPassword());
    }

    protected Map<String, String> getWarehouseConfig(String warehouseName,
                                                     String polarisCatalog
    ) {
        Map<String, String>               defaults        = super.getWarehouseConfig(warehouseName, polarisCatalog);
        Map<String, String>               warehouseConfig = new HashMap<>();
        defaults
            .entrySet().stream()
            .filter(x -> !x.getValue().contains(getSparkUser() + ":" + getSparkPassword()))
            .forEach(x -> warehouseConfig.put(x.getKey(), x.getValue()));

        warehouseConfig.put(String.format("spark.sql.catalog.%s.token", warehouseName), keyCloakToken);
        return warehouseConfig;
    }
}
