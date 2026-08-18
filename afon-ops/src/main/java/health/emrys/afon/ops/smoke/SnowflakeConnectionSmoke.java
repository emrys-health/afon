package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.snowflake.SnowflakeConnectionFactory;
import org.slf4j.Logger;

import java.sql.*;
import java.util.Properties;

/**
 * This needs the VM arg --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED
 */
public class SnowflakeConnectionSmoke {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(SnowflakeConnectionSmoke.class);

    public void selectFrom(String table, Properties properties) throws Exception {
        SnowflakeConnectionFactory factory = new SnowflakeConnectionFactory();

        try (Connection conn = factory.getConnection() ;
            Statement stmt = conn.createStatement()) {
            String sql = "SELECT * from " + table;
            logger.info("Running sql {}", sql);
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                System.out.println("id: " + rs.getString(1));
            }
        }

        logger.info("Finished calling Snowflake");
    }

    public static void main(String[] args) throws Exception {
        SnowflakeConnectionSmoke app = new SnowflakeConnectionSmoke();
        app.selectFrom("my_gcp_table_id", new FilePropertyProvider().getProperties());
    }


}
