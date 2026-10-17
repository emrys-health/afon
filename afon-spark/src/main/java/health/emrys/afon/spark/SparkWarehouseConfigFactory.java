package health.emrys.afon.spark;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;

import static health.emrys.afon.config.AfonProperty.*;

public class SparkWarehouseConfigFactory {

    public static SparkWarehouseConfigFactory getFactory() {
        return new SparkWarehouseConfigFactory();
    }

    public SparkWarehouseConfig createConfig(AfonPropertyProvider properties, String endpoint, AfonUser user) {
        properties.setValue(POLARIS_ENDPOINT, endpoint);
        return createConfig(properties, user);
    }

    /**
     * Creates a config authenticating as the configured user from users.csv, named by {@code USER_ID}.
     */
    public SparkWarehouseConfig createConfig(AfonPropertyProvider properties, AfonUserProvider users) {
        return createConfig(properties, users.getConfiguredUser(properties));
    }

    public SparkWarehouseConfig createConfig(AfonPropertyProvider properties, AfonUser user) {
        return new KeyCloakSparkWarehouseConfig(properties, user);
    }

}
