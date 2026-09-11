package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.GuacamoleConfig;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static java.lang.String.format;


public class GuacamoleDBSmoke {

    private static final Logger logger = LoggerFactory.getLogger(GuacamoleDBSmoke.class);

    public static void main(String[] args) {
        try {
            new GuacamoleDBSmoke().findAdminInQuacDb();
        } catch (Exception e) {
            logger.error("Failed to check Guacamole's DB", e);
        }
    }

    private void findAdminInQuacDb() throws SQLException {
        FilePropertyProvider     properties = new FilePropertyProvider();
        GuacamoleConfig.DbConfig db         = GuacamoleConfig.DbConfig.create(properties);
        String                   url        = format("jdbc:postgresql://%s:%d/%s", db.host(), db.port(), db.database());
        String                   guacadmin  = "guacadmin";
        String                   table      = "guacamole_entity";
        try (Connection conn = DriverManager.getConnection(url, db.user(), db.password());
             PreparedStatement pstmt = conn.prepareStatement(
                 String.format("SELECT * FROM %s WHERE name = '%s' AND type = 'USER'", table, guacadmin))) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    logger.warn(format(
                        "Found guacamole_entity %s in database %s",
                        rs.getString("name"),
                        db.database()));
                    throw new RuntimeException(String.format("'%s.%s' contains '%s'", db.database(), table, guacadmin));
                }
            }
        }
    }

}
