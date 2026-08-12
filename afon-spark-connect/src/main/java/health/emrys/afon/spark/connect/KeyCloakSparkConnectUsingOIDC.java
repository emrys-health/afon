package health.emrys.afon.spark.connect;

import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class KeyCloakSparkConnectUsingOIDC extends KeyCloakSparkConnect {

    private static final Logger log = LoggerFactory.getLogger(KeyCloakSparkConnectUsingOIDC.class);

    /**
     * First run:
     * kubectl port-forward pod/spark-connector-0-driver 15002:15002
     */
    public static void main(String[] args) {
        try {
            KeyCloakSparkConnect app = new KeyCloakSparkConnectUsingOIDC(args[0]);
            app.talkToSparkConnect();
        } catch (Exception x) {
            log.error(x.getMessage(), x);
            throw new RuntimeException(x);
        }

    }

    private final String keyCloakToken;

    public KeyCloakSparkConnectUsingOIDC(String polarisHost) throws IOException {
        super(polarisHost);
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
