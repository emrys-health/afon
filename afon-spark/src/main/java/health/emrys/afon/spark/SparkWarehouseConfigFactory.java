package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;

import static health.emrys.afon.config.AfonProperty.*;

public class SparkWarehouseConfigFactory {

    public static SparkWarehouseConfigFactory getFactory() {
        return new SparkWarehouseConfigFactory();
    }

    private static @NonNull AfonPropertyProvider propertiesWith(String clientId, String clientSecret, String host) {
        AfonPropertyProvider properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, host);
        properties.setValue(POLARIS_ID, clientId);
        properties.setValue(POLARIS_SECRET, clientSecret);
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
        return getConfig(propertiesWith(clientId, clientSecret, host));
    }

    public SparkWarehouseConfig getConfig(AfonPropertyProvider properties) {
        return new KeyCloakSparkWarehouseConfig(properties);
    }

}
