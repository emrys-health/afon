package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonUser;
import org.slf4j.Logger;

import java.util.List;

/**
 * Get your token (second argument) with
 * mvn -pl afon-ops exec:java -Dexec.mainClass=health.emrys.afon.ops.smoke.OidcUserTokenSmokeMain -Dexec.args=phil
 * or your username (not phil) which is the first argument.
 * <p>
 * Note: this is a fundamentally interactive process as it requires MFA.
 */
public class TrinoQueryWithTokenSmokeMain extends TrinoQuerySmokeMain {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TrinoQueryWithTokenSmokeMain.class);

    public static void main(String[] args) {
        String                       username = args[0];
        TrinoQueryWithTokenSmokeMain app      = new TrinoQueryWithTokenSmokeMain(username, args[1]);
        try {
            List<String> errors = app.checkCallingTrinoForAllUsers();
            if (!errors.isEmpty()) {
                String msg = String.join("\n", errors);
                logger.error(msg);
                throw new RuntimeException(msg);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }
        logger.info("Trino test for passed", username);
    }

    private final String username;
    private final String token;

    public TrinoQueryWithTokenSmokeMain(String username, String token) {
        this.username = username;
        this.token    = token;
    }

    @Override
    protected String clientUserName() {
        logger.info("Executing query on behalf of {}", username);
        return username;
    }

    @Override
    protected String getTokenFor(AfonUser user) {
        return token;
    }

}
