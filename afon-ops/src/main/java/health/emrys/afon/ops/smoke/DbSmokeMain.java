package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static java.lang.String.format;

public class DbSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(DbSmokeMain.class);

    private final AfonPropertyProvider properties;

    public static void main(String[] args) {
        DbSmokeMain  app    = new DbSmokeMain();
        List<String> errors = app.checkDbAccess();
        if (!errors.isEmpty()) {
            String msg = String.join("\n", errors);
            logger.error(msg);
            throw new RuntimeException(msg);
        }
    }

    public DbSmokeMain() {
        this.properties = SmokeConfig.smokeTestProperties();
    }

    private List<String> checkDbAccess() {
        List<String> errors = new ArrayList<>();
        errors.addAll(checkPolarisDbAccess());
        return errors;
    }

    private List<String> checkPolarisDbAccess() {
        List<String> errors = new ArrayList<>();
        // what is logged, and reported as an error, has no password: the url has it, and must not be logged
        String target = format("jdbc:postgresql://%s/", properties.getValue(POLARIS_DB_HOST));
        String url = format(
            "%s?user=%s&password=%s",
            target,
            properties.getValue(POLARIS_DB_USERNAME),
            properties.getValue(POLARIS_DB_PASSWORD)
        );
        logger.info("Connecting to {}", target);
        try {
            Connection connection = DriverManager.getConnection(url);
            ResultSet  resultSet  = connection.createStatement().executeQuery("SELECT 1");
            while (resultSet.next()) {
                logger.info("{} ", resultSet.getString(1));
            }
            errors.add(format("Was not blocked from talking to %s", target));
        } catch (SQLException e) {
            logger.info("Cannot connect to {}", target);
        }
        return errors;
    }
}
