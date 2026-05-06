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

import static health.emrys.afon.config.AfonProperties.SNOWFLAKE_JDBC_URL;
import static health.emrys.afon.config.AfonProperties.SNOWFLAKE_PRIVATE_KEY;

/**
 * This needs the VM arg --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED
 */
public class SmokeTest {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SmokeTest.class);

    public static void main(String[] args) throws Exception {
        SmokeTest app = new SmokeTest();
        app.selectFrom("my_gcp_table_id", new AfonPropertiesLoader().getProperties());
    }

    public void selectFrom(String table, Properties properties) throws Exception {
        SnowflakeConnectionFactory factory = new SnowflakeConnectionFactory();

        try (Connection conn = factory.getConnection() ;
             Statement stmt = conn.createStatement()) {
            String sql = "SELECT * from " + table;
            log.info("Running sql {}", sql);
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                System.out.println("id: " + rs.getString(1));
            }
        }

        log.info("Finished calling Snowflake");
    }


}
