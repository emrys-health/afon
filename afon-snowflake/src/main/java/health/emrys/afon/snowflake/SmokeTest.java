package health.emrys.afon.snowflake;

import health.emrys.afon.config.AfonPropertiesLoader;
import org.slf4j.Logger;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.*;
import java.util.Base64;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.SNOWFLAKE_PRIVATE_KEY;

/**
 * This needs the VM arg --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED
 * Assumes somebody has run in Snowflake:
 * <code>
 CREATE OR REPLACE CATALOG INTEGRATION iceberg_gcp_id
     CATALOG_SOURCE = ICEBERG_REST
     TABLE_FORMAT = ICEBERG
     REST_CONFIG = (
         CATALOG_URI = 'https://emryspolaris.click/api/catalog/',
         CATALOG_NAME = 'gcp'
         ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS
     )
     REST_AUTHENTICATION = (
         TYPE = OAUTH
         OAUTH_CLIENT_ID = 'root'
         OAUTH_CLIENT_SECRET = 's3cr3t'
         OAUTH_ALLOWED_SCOPES = ('PRINCIPAL_ROLE:ALL')
     )
     ENABLED = TRUE;

 USE DATABASE SNOWFLAKE_LEARNING_DB;

 CREATE OR REPLACE ICEBERG TABLE my_gcp_table_id
     CATALOG = 'iceberg_gcp_id'
     CATALOG_TABLE_NAME = 'GcpAccessSpec260430301114'
     CATALOG_NAMESPACE = 'acceptancetests'
     REPLACE_INVALID_CHARACTERS = TRUE;
</code>
 * Note that the CATALOG_TABLE_NAME doesn't need to be this legacy acceptance test relic.
 */
public class SmokeTest {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SmokeTest.class);

    public static void main(String[] args) throws Exception {
        SmokeTest app = new SmokeTest();
        app.smokeTest();
    }

    public void smokeTest() throws Exception {
        String url =
            "jdbc:snowflake://PAFHYMJ-JC78064.snowflakecomputing.com/";

        Properties afonProperties = new AfonPropertiesLoader().getProperties();
        PrivateKey privateKey     = getSnowflakePrivateKey(afonProperties);

        log.info("Attempting to connect to database at {} ...", url);
        try (Connection conn = getConnection(privateKey, url);
             Statement stmt = conn.createStatement()) {
            String sql = "SELECT * from my_gcp_table_id";
            log.info("Running sql {}", sql);
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                System.out.println("id: " + rs.getString(1));
            }
        }

        log.info("Finished calling Snowflake");
    }

    private Connection getConnection(PrivateKey privateKey, String url) throws SQLException {
        Properties props = new Properties();
        props.put("user", "ETL_SERVICE_USER");
        props.put("db", "SNOWFLAKE_LEARNING_DB");
        props.put("schema", "PUBLIC");
        props.put("warehouse", "COMPUTE_WH");
        props.put("role", "ACCOUNTADMIN");
        props.put("privateKey", privateKey);
        return DriverManager.getConnection(url, props);
    }

    private PrivateKey getSnowflakePrivateKey(Properties afonProperties) throws NoSuchAlgorithmException,
                                                                                InvalidKeySpecException {
        String              keyFileContent = SNOWFLAKE_PRIVATE_KEY.getValueFrom(afonProperties);
        byte[]              decodedKey     = Base64.getDecoder().decode(keyFileContent);
        PKCS8EncodedKeySpec keySpec        = new PKCS8EncodedKeySpec(decodedKey);
        KeyFactory          kf             = KeyFactory.getInstance("RSA");
        PrivateKey          privateKey     = kf.generatePrivate(keySpec);
        return privateKey;
    }
}
