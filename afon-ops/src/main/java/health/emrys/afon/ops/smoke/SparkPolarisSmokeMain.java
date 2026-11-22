package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsDefaults;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.ops.spark.SparkTestReader;
import health.emrys.afon.ops.spark.SparkTestWriter;
import health.emrys.afon.ops.steps.StepOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.config.AfonCatalog.catalogFor;
import static health.emrys.afon.config.AfonProperty.POLARIS_ACCESS;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperty.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.config.AfonProperty.POLARIS_TABLE_NAME;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.isProject;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;

/**
 * Smoke test that Spark writes a table through the Polaris of the stack to the stack's own catalog, and reads it back,
 * for the cloud of the stack hosting the TRE ({@code TRE_CLOUD}), eg:
 * <p>
 * ./afon.sh -a spark-polaris-smoke
 * <p>
 * It is the check that the setup of a stack used to end with.  The Spark is the session of this process, and Polaris is called from
 * this machine, through a port-forward to its pod, or through its public domain name, as {@code POLARIS_ACCESS} says.  It writes the
 * table that {@link SparkConnectSmokeMain} and {@link TrinoQuerySmokeMain} read, so it runs before them in the suite.
 * <p>
 * Not run for a project's TRE, whose Polaris admits only the tokens of the project, with its audience, its {@code acr} and its
 * project claim, which the admin user that this signs in as cannot present: it passes, saying so.
 */
public class SparkPolarisSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(SparkPolarisSmokeMain.class);

    private final AfonPropertyProvider properties;
    private final AfonCloud            cloud;

    SparkPolarisSmokeMain(AfonPropertyProvider properties) {
        this.cloud = new SmokeConfig(properties).cloud();
        // as seen by the cloud, whose Polaris the smoke test calls
        this.properties = properties.forCloud(cloud);
    }

    void run() throws Exception {
        if (isProject(properties)) {
            logger.info("Skipping: the Polaris of a project's TRE admits only the tokens of the project, which this test cannot present");
            return;
        }
        String[] args = {cloud.toString(), catalogFor(cloud, new AfonCatalogs(properties)).toString(),
                         properties.getValue(POLARIS_NAMESPACE_EXTERNAL, OpsDefaults.POLARIS_NAMESPACE_EXTERNAL),
                         properties.getValue(POLARIS_TABLE_NAME, OpsDefaults.POLARIS_TABLE_NAME)};
        if (viaPortForward(properties.getValue(POLARIS_ACCESS, "port-forward"))) {
            try (KubeClient client = new KubeClient(properties)) {
                client.requireContextOf(cloud);
                String pod = client.runningPodWithLabel(StepOperations.POLARIS_POD_LABEL).orElseThrow(() -> new IllegalStateException(
                    "No running pod with the label " + StepOperations.POLARIS_POD_LABEL + " in the kubectl context '" + client.getContext() + "'"));
                try (PortForwarder forward = new PortForwarder(client, "default", pod, 0, StepOperations.POLARIS_PORT)) {
                    writeThenRead("localhost:" + forward.localPort(), args);
                }
            }
        } else {
            writeThenRead(null, args);
        }
        logger.info("Test successful. Spark wrote {}.{}.{} through the Polaris of {} and read it back", args[1], args[2], args[3], cloud);
    }

    static boolean viaPortForward(String access) {
        if (access == null || access.isBlank() || access.equalsIgnoreCase("port-forward")) {
            return true;
        }
        if (access.equalsIgnoreCase("https")) {
            return false;
        }
        throw new IllegalArgumentException(String.format("%s should be 'https' or 'port-forward', not '%s'", POLARIS_ACCESS, access));
    }

    /**
     * Runs the writer then the reader, which each sign in as the configured user and fail if Polaris refuses.
     *
     * @param endpoint where to call Polaris, or {@code null} for its public domain name
     */
    private static void writeThenRead(String endpoint, String[] args) {
        String name     = POLARIS_ENDPOINT.getName();
        String original = System.getProperty(name);
        if (endpoint != null) {
            System.setProperty(name, endpoint);
        }
        try {
            SparkTestWriter.main(args);
            SparkTestReader.main(args);
        } finally {
            if (original != null) {
                System.setProperty(name, original);
            } else {
                System.clearProperty(name);
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            new SparkPolarisSmokeMain(smokeTestProperties()).run();
        } catch (Exception e) {
            logger.error("Spark did not write and read the table through Polaris: {}", e.getMessage(), e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
