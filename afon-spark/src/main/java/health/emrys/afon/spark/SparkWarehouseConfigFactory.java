package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class SparkWarehouseConfigFactory {

    public static SparkWarehouseConfigFactory getFactory() {
        return new SparkWarehouseConfigFactory();
    }

    private static @NotNull Properties propertiesWith(String clientId, String clientSecret, String host) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        properties.put(POLARIS_ID.getPropertyName(), clientId);
        properties.put(POLARIS_SECRET.getPropertyName(), clientSecret);
        properties.put(POLARIS_ENDPOINT.getPropertyName(), host);
        return properties;
    }

    public SparkWarehouseConfig getConfig(
        String clientId,
        String clientSecret,
        String host,
        String polarisProtocol) {
        return getConfig(clientId, clientSecret, host);
    }

    public SparkWarehouseConfig getConfig(
        String clientId,
        String clientSecret,
        String host) {
        Properties properties = propertiesWith(clientId, clientSecret, host);
        return new KeyCloakSparkWarehouseConfig(properties);
    }

    public SparkWarehouseConfig getConfig(Properties properties) {
        return new KeyCloakSparkWarehouseConfig(properties);
    }

}
