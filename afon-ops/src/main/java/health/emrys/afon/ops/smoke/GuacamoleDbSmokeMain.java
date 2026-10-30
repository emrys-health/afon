package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.DockerImages;
import health.emrys.afon.cdk.configs.GuacamoleConfig;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static java.lang.String.format;


/**
 * Checks that Guacamole's DB holds no {@code guacadmin} user. Access to the DB is restricted to the cluster's (NAT) egress IP, so
 * the query is run by psql in a throwaway pod ({@code kubectl run --rm}) rather than from here. The password is written to the
 * pod's stdin so that it appears in neither the pod spec nor the local process list.
 */
public class GuacamoleDbSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(GuacamoleDbSmokeMain.class);

    // Blocks on the password, which also stops the pod from finishing before kubectl has attached to its output
    private static final String PSQL_SCRIPT = """
                                              read -r PGPASSWORD; export PGPASSWORD
                                              psql -v ON_ERROR_STOP=1 -tAc "$SQL"
                                              """;

    private final AfonPropertyProvider properties;

    public GuacamoleDbSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private void findAdmin() throws IOException, InterruptedException {
        GuacamoleConfig.DbConfig db        = GuacamoleConfig.DbConfig.create(properties);
        String                   guacadmin = "guacadmin";
        String                   table     = "guacamole_entity";
        String                   sql       = format("SELECT count(*) FROM %s WHERE name = '%s' AND type = 'USER'", table, guacadmin);
        String                   pod       = "guacamole-db-smoke-" + System.currentTimeMillis();
        List<String> command = List.of(
            "kubectl", "run", pod, "--namespace", "default", "--rm", "-i", "--quiet", "--restart=Never",
            "--pod-running-timeout=5m", "--image", DockerImages.POSTGRES_ALPINE_IMAGE,
            "--env", "PGHOST=" + db.host(),
            "--env", "PGPORT=" + db.port(),
            "--env", "PGDATABASE=" + db.database(),
            "--env", "PGUSER=" + db.user(),
            "--env", "SQL=" + sql,
            "--", "sh", "-c", PSQL_SCRIPT);
        logger.info("About to execute SQL in pod {} against {}: {}, by running:\n{}",
                    pod, db.database(), sql, command.stream().collect(Collectors.joining(" ")));

        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((db.password() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(6, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new RuntimeException(format("Timed out querying %s from pod %s", db.database(), pod));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new RuntimeException(format("Could not query %s from pod %s (exit %d): %s", db.database(), pod, process.exitValue(), output));
        }
        String[] lines = output.split("\\R");
        logger.info("Output:\n{}", String.join("\n", lines));
        long     count = Long.parseLong(lines[lines.length - 1].trim());
        if (count > 0) {
            throw new RuntimeException(format("'%s.%s' contains '%s'", db.database(), table, guacadmin));
        }
        logger.info("No {} in table {} of {}", guacadmin, table, db.database());
    }

    public static void main(String[] args) {
        int exitCode = 0;
        var app = new GuacamoleDbSmokeMain(smokeTestProperties());
        try {
            app.findAdmin();
        } catch (Exception e) {
            logger.error("Failed to check Guacamole's DB", e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

}
