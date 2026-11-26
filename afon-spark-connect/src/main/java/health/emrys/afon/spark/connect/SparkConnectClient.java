package health.emrys.afon.spark.connect;

import com.google.common.annotations.VisibleForTesting;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
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
import static health.emrys.afon.spark.Constants.TOKEN_HEADER;

/**
 * A thin client for the <a href="https://spark.apache.org/spark-connect/">Spark Connect</a> server
 * deployed in the cluster (the {@code spark-connector-0-driver} pod), reached over gRPC via a
 * {@code sc://} connection string (see {@link #DEFAULT_SPARK_CONNECT_URL}, configurable via
 * {@link AfonProperty#SPARK_CONNECT_URL}).
 * <p>
 * Unlike {@code SparkClient}, which builds a full, in-process {@link SparkSession},
 * {@link SparkConnectClient} starts no local Spark runtime at all: {@link #sparkSession}
 * just builds a remote-mode {@link SparkSession} that forwards queries to the already-running
 * server, which owns the actual driver/executors, so multiple clients can share one long-lived
 * Spark deployment instead of each spinning up their own.
 * <p>
 * The server is shared, so it holds no catalogs or credentials: each session registers its own
 * catalog, authenticated with an OIDC token so that Polaris authorises the calling user - just as
 * a data scientist's notebook does with the {@code OIDC_TOKEN} injected into their pod.
 * <p>
 * Requires the server to be reachable at the configured URL, typically via
 * {@code kubectl port-forward pod/spark-connector-0-driver 15002:15002}.
 */
public class SparkConnectClient {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectClient.class);

    public static final String DEFAULT_SPARK_CONNECT_URL = "sc://localhost:15002";

    private final String               connectionString;
    private final SparkWarehouseConfig sparkConfig;
    private final String               token;

    /**
     * Authenticates as the configured user, from users.csv, named by {@link AfonProperty#USER_ID},
     * whose OIDC token is obtained from KeyCloak.
     */
    public SparkConnectClient(AfonPropertyProvider properties, AfonUserProvider users) throws IOException {
        this(properties, users.getConfiguredUser(properties));
    }

    public SparkConnectClient(AfonPropertyProvider properties, AfonUser user) throws IOException {
        this(properties, user, userToken(properties, user));
    }

    @VisibleForTesting
    public SparkConnectClient(AfonPropertyProvider properties, AfonUser user, String token) {
        logger.info("Using Polaris Endpoint {}", properties.getValue(POLARIS_ENDPOINT));
        this.connectionString = properties.getValue(SPARK_CONNECT_URL, DEFAULT_SPARK_CONNECT_URL);
        this.sparkConfig      = SparkWarehouseConfigFactory.getFactory().createConfig(properties, user);
        this.token            = token;
    }

    /**
     * @return the connection string, with the token in the header that the server takes it from: a parameter of the connection
     * string that is not one of Spark Connect's own is sent as a header with every call
     */
    public static String withToken(String connectionString, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Spark Connect needs an OIDC token");
        }
        String base = connectionString.contains("/;") ? connectionString : connectionString.replaceAll("/+$", "") + "/";
        return base + ";" + TOKEN_HEADER + "=" + token;
    }

    /**
     * @return the OIDC token of the user's confidential client
     */
    public static String userToken(AfonPropertyProvider properties, AfonUser user) throws IOException {
        // The user's confidential client, of the same name and secret, as it takes no username/password
        return new KeyCloakProvider(properties.getValue(OIDC_DOMAIN_NAME)).clientToken(user.username(), user.password());
    }

    /**
     * Creates a {@link SparkSession} for the given warehouse.
     *
     * @param warehouse   the Spark warehouse
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
            .remote(withToken(connectionString, token));

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
