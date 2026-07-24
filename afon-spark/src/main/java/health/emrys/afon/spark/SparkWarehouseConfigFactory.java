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
        properties.put(POLARIS_ENDPOINT.getName(), host);
        return propertiesWith(clientId, clientSecret, properties);
    }

    private static @NotNull Properties propertiesWith(String clientId, String clientSecret, Properties defaults) {
        Properties properties = new Properties();
        properties.putAll(defaults);
        properties.put(POLARIS_ID.getName(), clientId);
        properties.put(POLARIS_SECRET.getName(), clientSecret);
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
        return getConfig(properties);
    }

    public SparkWarehouseConfig getConfig(
        String clientId,
        String clientSecret,
        Properties defaults) {
        Properties properties = propertiesWith(clientId, clientSecret, defaults);
        return getConfig(properties);
    }

    public SparkWarehouseConfig getConfig(Properties properties) {
        return new KeyCloakSparkWarehouseConfig(properties);
    }

}
