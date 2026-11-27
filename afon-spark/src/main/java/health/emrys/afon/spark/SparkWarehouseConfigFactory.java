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

    /**
     * Creates a config that reads only the catalogs of Data Providers, with the caller's own token, see
     * {@link SparkWarehouseConfig#sparkConfigsWithToken(health.emrys.afon.config.AfonCatalog, String, String)}: a member of a project has no client of the stack's, nor
     * a Polaris of the stack's to call, as what answers is each Data Provider's.
     */
    public SparkWarehouseConfig createDataProviderConfig(AfonPropertyProvider properties) {
        return new SparkWarehouseConfig("", "", "", "", new health.emrys.afon.config.AfonCatalogs(properties));
    }

}
