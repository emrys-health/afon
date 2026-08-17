package health.emrys.afon.snowflake;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
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

    public SnowflakeConnectionFactory() throws NoSuchAlgorithmException, InvalidKeySpecException {
        AfonPropertyProvider afonProperties = new FilePropertyProvider();
        privateKey = getSnowflakePrivateKey(afonProperties);
        url        = afonProperties.getValue(SNOWFLAKE_JDBC_URL);
        log.info("Attempting to connect to database at {} ...", url);
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
