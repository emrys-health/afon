package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.snowflake.SnowflakeConnectionFactory;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * This needs the VM arg --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED
 *
 * Provide table name as arg, eg "my_gcp_table_id"
 */
public class SnowflakeQuerySmoke {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(SnowflakeQuerySmoke.class);
    private final AfonPropertyProvider properties;

    public SnowflakeQuerySmoke(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public void query(String tableName) {
        try {
            SnowflakeConnectionFactory factory = new SnowflakeConnectionFactory(properties);
            Connection conn = factory.getConnection() ;
            Statement stmt = conn.createStatement();
            String sql = "SELECT * from " + tableName;
            logger.info("Running sql {}", sql);
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                System.out.println("id: " + rs.getString(1));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        logger.info("Finished calling Snowflake");
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.printf("%s requires argument: table-name", SnowflakeQuerySmoke.class.getSimpleName());
            return;
        }
        String tableName = args[0];
        var properties = new FilePropertyProvider();
        new SnowflakeQuerySmoke(properties).query(tableName);
    }
}
