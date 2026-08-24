package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.*;

public class SparkWarehouseConfigFactory {

    public static SparkWarehouseConfigFactory getFactory() {
        return new SparkWarehouseConfigFactory();
    }

    public SparkWarehouseConfig createConfig(
            AfonPropertyProvider properties, String endpoint, String clientId,
            String clientSecret) {
        properties.setValue(POLARIS_ENDPOINT, endpoint);
        properties.setValue(POLARIS_ID, clientId);
        properties.setValue(POLARIS_SECRET, clientSecret);
        return createConfig(properties);
    }

    public SparkWarehouseConfig createConfig(AfonPropertyProvider properties) {
        return new KeyCloakSparkWarehouseConfig(properties);
    }

}
