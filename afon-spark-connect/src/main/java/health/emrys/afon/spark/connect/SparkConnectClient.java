package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperty.SPARK_CONNECT_URL;

/**
 * A thin client for the <a href="https://spark.apache.org/spark-connect/">Spark Connect</a> server
 * deployed in the cluster (the {@code spark-connector-0-driver} pod), reached over gRPC via a
 * {@code sc://} connection string (see {@link #DEFAULT_SPARK_CONNECT_URL}, configurable via
 * {@link AfonProperty#SPARK_CONNECT_URL}).
 *
 * Unlike {@code SparkRunner}, which builds a full, in-process {@link SparkSession},
 * {@link SparkConnectClient} starts no local Spark runtime at all: {@link #sparkSession}
 * and {@link #sparkSessionAllWarehouses} just build a remote-mode {@link SparkSession} that
 * forwards queries to the already-running server, which owns the actual driver/executors,
 * so multiple clients can share one long-lived Spark deployment instead of each spinning up
 * their own.
 *
 * Requires the server to be reachable at the configured URL, typically via
 * {@code kubectl port-forward pod/spark-connector-0-driver 15002:15002}.
 */
public class SparkConnectClient {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectClient.class);

    public static final String DEFAULT_SPARK_CONNECT_URL = "sc://localhost:15002/;token=deadbeef";

    private final String connectionString;
    private final SparkWarehouseConfig sparkConfig;

    public SparkConnectClient(AfonPropertyProvider properties) {
        this.connectionString = properties.getValue(SPARK_CONNECT_URL, DEFAULT_SPARK_CONNECT_URL);
        logger.info("Using Polaris Endpoint {}", properties.getValue(POLARIS_ENDPOINT));
        this.sparkConfig = SparkWarehouseConfigFactory.getFactory().createConfig(properties);
    }

    /**
     * Creates a {@link SparkSession} for the given warehouse.
     *
     * @param warehouse the Spark warehouse
     * @param catalogName the Polaris catalog name
     */
    public SparkSession sparkSession(String warehouse, String catalogName) {
        return buildSparkSession(warehouseConfig(warehouse, catalogName));
    }

    /**
     * Creates a {@link SparkSession} for every {@link AfonCatalogs} catalog registered
     * on the session (plus the federated catalog), matching what the deployed Spark cluster is
     * configured with by {@code SparkWorkloads#userSparkWarehouseConfig}.
     * @param additionalCatalogNames the additional catalog names
     */
    public SparkSession sparkSessionAllWarehouses(String... additionalCatalogNames) {
        return buildSparkSession(allWarehousesConfig(additionalCatalogNames));
    }

    private SparkSession buildSparkSession(Map<String, String> config) {
        SparkSession.Builder builder = SparkSession
                .builder()
                .classic()
                .appName(this.getClass().getSimpleName())
                .remote(connectionString);

        final StringBuffer sb = new StringBuffer();
        config.entrySet()
              .forEach(entry -> {
                  String key   = entry.getKey();
                  String value = entry.getValue();
                  builder.config(key, value);
                  sb.append(String.format("%s=%s\n", key, value));
              });
        logger.info("Building SparkSession with config:\n{}", sb);
        return builder.getOrCreate();
    }

    protected Map<String, String> warehouseConfig(String warehouseName, String catalogName) {
        return sparkConfig.sparkConfigs(warehouseName, catalogName);
    }

    protected Map<String, String> allWarehousesConfig(String... additionalCatalogNames) {
        return sparkConfig.allSparkConfigs(additionalCatalogNames);
    }

    protected String getClientId() {
        return sparkConfig.getClientId();
    }

    protected String getClientSecret() {
        return sparkConfig.getClientSecret();
    }

}
