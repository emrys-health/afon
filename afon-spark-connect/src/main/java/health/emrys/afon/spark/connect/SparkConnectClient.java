package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.OIDC_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperty.SPARK_CONNECT_URL;
import static health.emrys.afon.config.AfonProperty.USER_POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.USER_POLARIS_SECRET;

/**
 * A thin client for the <a href="https://spark.apache.org/spark-connect/">Spark Connect</a> server
 * deployed in the cluster (the {@code spark-connector-0-driver} pod), reached over gRPC via a
 * {@code sc://} connection string (see {@link #DEFAULT_SPARK_CONNECT_URL}, configurable via
 * {@link AfonProperty#SPARK_CONNECT_URL}).
 *
 * Unlike {@code SparkClient}, which builds a full, in-process {@link SparkSession},
 * {@link SparkConnectClient} starts no local Spark runtime at all: {@link #sparkSession}
 * just builds a remote-mode {@link SparkSession} that forwards queries to the already-running
 * server, which owns the actual driver/executors, so multiple clients can share one long-lived
 * Spark deployment instead of each spinning up their own.
 *
 * The server is shared, so it holds no catalogs or credentials: each session registers its own
 * catalog, authenticated with an OIDC token so that Polaris authorises the calling user - just as
 * a data scientist's notebook does with the {@code OIDC_TOKEN} injected into their pod.
 *
 * Requires the server to be reachable at the configured URL, typically via
 * {@code kubectl port-forward pod/spark-connector-0-driver 15002:15002}.
 */
public class SparkConnectClient {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectClient.class);

    public static final String DEFAULT_SPARK_CONNECT_URL = "sc://localhost:15002/;token=deadbeef";

    private final String connectionString;
    private final SparkWarehouseConfig sparkConfig;
    private final String token;

    /**
     * Authenticates as the {@link AfonProperty#USER_POLARIS_ID} user, whose OIDC token is obtained
     * from KeyCloak.
     */
    public SparkConnectClient(AfonPropertyProvider properties) throws IOException {
        this(properties, userToken(properties));
    }

    public SparkConnectClient(AfonPropertyProvider properties, String token) {
        this.connectionString = properties.getValue(SPARK_CONNECT_URL, DEFAULT_SPARK_CONNECT_URL);
        logger.info("Using Polaris Endpoint {}", properties.getValue(POLARIS_ENDPOINT));
        this.sparkConfig = SparkWarehouseConfigFactory.getFactory().createConfig(properties);
        this.token = token;
    }

    private static String userToken(AfonPropertyProvider properties) throws IOException {
        return new KeyCloakProvider(properties.getValue(OIDC_DOMAIN_NAME)).userToken(properties.getValue(USER_POLARIS_ID),
                                                          properties.getValue(USER_POLARIS_SECRET));
    }

    /**
     * Creates a {@link SparkSession} for the given warehouse.
     *
     * @param warehouse the Spark warehouse
     * @param catalogName the Polaris catalog name
     */
    public SparkSession sparkSession(String warehouse, String catalogName) {
        return buildSparkSession(sparkConfig.sparkConfigsWithToken(warehouse, catalogName, token));
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
                  sb.append(String.format("%s=%s\n", key, key.endsWith(".token") ? "<redacted>" : value));
              });
        logger.info("Building SparkSession with config:\n{}", sb);
        return builder.getOrCreate();
    }

}
