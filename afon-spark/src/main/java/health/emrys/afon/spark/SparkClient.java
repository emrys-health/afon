package health.emrys.afon.spark;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.polaris.config.PolarisCredentials;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * A client that builds a full, in-process {@link SparkSession}, in which the JVM is
 * the Spark driver, with executors started locally by the {@link SparkWarehouseConfig}
 * built from {@link PolarisCredentials} resolved for the given {@link AfonCloud}.
 */
public class SparkClient {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkClient.class);

    private final AfonPropertyProvider properties;
    private final PolarisCredentials credentials;
    private final AfonUser user;

    /**
     * Authenticates as the configured user, from users.csv, named by {@code USER_ID}.
     */
    public SparkClient(AfonCloud cloud, AfonPropertyProvider properties) {
        this(cloud, properties, new FileUserProvider().getConfiguredUser(properties));
    }

    public SparkClient(AfonCloud cloud, AfonPropertyProvider properties, AfonUser user) {
        this.properties = properties;
        this.credentials = new PolarisCredentials(cloud, properties);
        this.user = user;
    }

    /**
     * Creates a {@link SparkSession} for the given warehouses.
     *
     * @param warehouseNames the Spark warehouse/catalog names to configure
     */
    public SparkSession sparkSession(String... warehouseNames) throws IOException {
        SparkContext.getOrCreate(createWarehouseConfig().sparkConf(4, warehouseNames));
        return SparkSession.builder().getOrCreate();
    }

    /**
     *  Creates a {@link SparkSession} for every {@link AfonCatalogs} catalog registered
     */
    public SparkSession sparkSessionAllWarehouses() throws IOException {
        SparkContext.getOrCreate(createWarehouseConfig().sparkConf());
        return SparkSession.builder().getOrCreate();
    }

    private SparkWarehouseConfig createWarehouseConfig() {
        logger.info("SparkConfig for Polaris endpoint {} and user {}",
                credentials.getPolarisEndpoint(), user.username());
        return SparkWarehouseConfigFactory.getFactory()
                .createConfig(properties, credentials.getPolarisEndpoint(), user);
    }

}
