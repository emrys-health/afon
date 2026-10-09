package health.emrys.afon.snowflake;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.SNOWFLAKE_JDBC_URL;
import static health.emrys.afon.config.AfonProperty.SNOWFLAKE_PRIVATE_KEY;

public class SnowflakeConnectionFactory {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SnowflakeConnectionFactory.class);

    private final PrivateKey privateKey;
    private final String url;

    /**
     * @throws IllegalStateException if {@code SNOWFLAKE_JDBC_URL} or {@code SNOWFLAKE_PRIVATE_KEY} is blank, as the driver
     *                               would otherwise fail with "No suitable driver found for " and no URL
     */
    public SnowflakeConnectionFactory(AfonPropertyProvider properties) throws NoSuchAlgorithmException, InvalidKeySpecException {
        requireSet(properties, SNOWFLAKE_JDBC_URL);
        requireSet(properties, SNOWFLAKE_PRIVATE_KEY);
        privateKey = getSnowflakePrivateKey(properties);
        url        = properties.getValue(SNOWFLAKE_JDBC_URL);
        log.info("Attempting to connect to database at {} ...", url);
    }

    /**
     * The seconds after which Snowflake cancels a statement it cannot finish, eg as it cannot reach Polaris, so that it fails
     * rather than hangs.  Statements such as creating an Iceberg table can take several minutes, eg when reading the GCP catalog.
     */
    public static final int QUERY_TIMEOUT_SECONDS = 600;

    private static void requireSet(AfonPropertyProvider properties, AfonProperty property) {
        if (properties.getValue(property, "").isBlank()) {
            throw new IllegalStateException(String.format(
                "%s is not set: configure it to use Snowflake, eg in the config dir", property.getName()));
        }
    }

    public Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.put("user", "ETL_SERVICE_USER");
        props.put("db", "SNOWFLAKE_LEARNING_DB");
        props.put("schema", "PUBLIC");
        props.put("warehouse", "COMPUTE_WH");
        props.put("role", "ACCOUNTADMIN");
        props.put("privateKey", privateKey);
//        props.put("statement_timeout_in_seconds", "120");
        return DriverManager.getConnection(url, props);
    }

    private PrivateKey getSnowflakePrivateKey(AfonPropertyProvider afonProperties) throws NoSuchAlgorithmException,
                                                                                InvalidKeySpecException {
        String              keyFileContent = afonProperties.getValue(SNOWFLAKE_PRIVATE_KEY);
        byte[]              decodedKey     = Base64.getDecoder().decode(keyFileContent);
        PKCS8EncodedKeySpec keySpec        = new PKCS8EncodedKeySpec(decodedKey);
        KeyFactory          kf             = KeyFactory.getInstance("RSA");
        PrivateKey          privateKey     = kf.generatePrivate(keySpec);
        return privateKey;
    }

}
