package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.GuacamoleConfig;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

import static java.lang.String.format;


public class GuacamoleDBSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(GuacamoleDBSmokeMain.class);
    private final AfonPropertyProvider properties;

    public GuacamoleDBSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private void findAdmin() throws SQLException {
        GuacamoleConfig.DbConfig db         = GuacamoleConfig.DbConfig.create(properties);
        String                   url        = format("jdbc:postgresql://%s:%d/%s", db.host(), db.port(), db.database());
        String                   guacadmin  = "guacadmin";
        String                   table      = "guacamole_entity";
        String                   sql        = format("SELECT * FROM %s WHERE name = '%s' AND type = 'USER'", table, guacadmin);
        logger.info("Connecting to {}", db.database());
        try (Connection conn = DriverManager.getConnection(url, db.user(), db.password());
             PreparedStatement pstmt = conn.prepareStatement(
                 sql)) {
            logger.info("About to run: {}", sql);
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
        logger.info("No {} in table {} at {}", guacadmin, table, url);
    }

    public static void main(String[] args) {
        int exitCode = 0;
        AfonPropertyProvider properties = new OpsPropertyProvider();
        var app = new GuacamoleDBSmokeMain(properties);
        try {
            app.findAdmin();
        } catch (Exception e) {
            logger.error("Failed to check Guacamole's DB", e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

}
