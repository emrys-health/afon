package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.config.PolarisProjectGrants;
import health.emrys.afon.polaris.config.PolarisEndpoint;
import health.emrys.afon.ops.config.OpsDefaults;
import health.emrys.afon.ops.kube.tools.PolarisAccess;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.ops.spark.SparkTestReader;
import health.emrys.afon.ops.spark.SparkTestWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static health.emrys.afon.config.AfonCatalog.catalogFor;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperty.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.config.AfonProperty.POLARIS_TABLE_NAME;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.isProjectStack;
import static health.emrys.afon.ops.spark.SparkRunner.isDataProjectStack;
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
 * In a Data Provider's project stack it writes a table in the staging namespace of its first catalog, as the admin client of its Polaris, see {@link #targets()}.
 * <p>
 * Not run for a project stack's TRE, whose Polaris admits only the tokens of the project stack, with its audience, its {@code acr} and its
 * project claim, which the admin user that this signs in as cannot present: it passes, saying so.
 */
public class SparkPolarisSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(SparkPolarisSmokeMain.class);

    private final AfonPropertyProvider properties;
    private final AfonCloud            cloud;

    SparkPolarisSmokeMain(AfonPropertyProvider properties) {
        this.cloud = isDataProjectStack(properties) ? cloudOfTheCatalogs(properties) : new SmokeConfig(properties).cloud();
        // as seen by the cloud, whose Polaris the smoke test calls
        this.properties = properties.forCloud(cloud);
    }

    /**
     * In a Data Provider's project stack, a table that is in {@value #STAGING} or in the namespace that the project stacks read has been written already, and is not
     * written again: writing it in {@value #STAGING} when it has been released would leave two, and the release of the new one would clash with the old.
     * What the test writes is the first time only, which is what makes it, and then the release, safe to repeat.
     *
     * @param endpoint where Polaris is called, or {@code null} for its public domain name
     * @return the targets whose table is in neither, all of them outside a project
     */
    List<String[]> notThereYet(List<String[]> targets, String endpoint) throws Exception {
        if (!isDataProjectStack(properties)) {
            return targets;
        }
        properties.setValue(POLARIS_ENDPOINT, endpoint != null ? endpoint : new PolarisEndpoint(cloud, properties).getEndpoint());
        PolarisProjectGrants grants = new PolarisProjectGrants(properties);
        List<String[]> missing = new java.util.ArrayList<>();
        for (String[] target : targets) {
            boolean there = grants.tableExists(target[1], PolarisProjectGrants.STAGING, target[3])
                            || grants.tableExists(target[1], PolarisProjectGrants.RELEASED, target[3]);
            if (!there) {
                missing.add(target);
            }
        }
        return missing;
    }

    /**
     * @return the cloud of a Data Provider's project stack, which is the one whose catalogs it has: the config empties those of the others, as it is not a
     * TRE, which hosts its tests on the cloud that {@code TRE_CLOUD} names
     */
    private static AfonCloud cloudOfTheCatalogs(AfonPropertyProvider properties) {
        AfonCatalogs catalogs = new AfonCatalogs(properties);
        return java.util.Arrays.stream(AfonCloud.values()).filter(c -> !catalogs.byCloud(c).isEmpty()).findFirst()
                               .orElseThrow(() -> new IllegalStateException("The Data Provider's project stack has no catalog of its own"));
    }

    void run() throws Exception {
        if (isProjectStack(properties)) {
            logger.info("Skipping: the Polaris of a project stack's TRE admits only the tokens of the project stack, which this test cannot present");
            return;
        }
        List<String[]> targets = targets();
        List<String[]> all = targets;
        try (PolarisAccess access = PolarisAccess.open(properties, cloud)) {
            targets = notThereYet(targets, access.endpoint());
            for (String[] target : targets) {
                writeThenRead(access.endpoint(), target);
            }
        }
        for (String[] target : all) {
            if (!targets.contains(target)) {
                logger.info("{} of {} is in {} or in {} already, so Spark did not write it again", target[3], target[1], PolarisProjectGrants.STAGING, PolarisProjectGrants.RELEASED);
                continue;
            }
            logger.info("Test successful. Spark wrote {}.{}.{} through the Polaris of {} and read it back", target[1], target[2], target[3], cloud);
        }
    }

    /**
     * What is written, as the arguments of the writer and the reader: the cloud, the catalog, the namespace and the table.
     *
     * <p>Outside a project, the stack's own catalog, which the TRE's tests then read.  In a Data Provider's project stack, the first of its own catalogs,
     * in {@value #STAGING}, the one namespace in which it makes its tables, and which its project stacks may not read: it is the table that a Data Provider
     * makes to try its Polaris, which it then releases to the project stacks that it has let in ({@code manage-data release}), for them to read,
     * so that what a TRE reads from it is a table with rows.  The test is the same, and signs in as the admin of the Polaris it is run for.
     */
    List<String[]> targets() {
        if (isDataProjectStack(properties)) {
            List<AfonCatalog> own = new AfonCatalogs(properties).byCloud(cloud);
            if (own.isEmpty()) {
                throw new IllegalStateException("The Data Provider's project stack has no catalog of its own on " + cloud + " to write the table in");
            }
            String catalog = own.get(0).toString();
            return List.of(new String[]{cloud.toString(), catalog, STAGING, DATA_PROVIDER_TABLE},
                           new String[]{cloud.toString(), catalog, STAGING, DATA_PROVIDER_UNRELEASED_TABLE});
        }
        return java.util.Collections.singletonList(new String[]{cloud.toString(), catalogFor(cloud, new AfonCatalogs(properties)).toString(),
                                                                         properties.getValue(POLARIS_NAMESPACE_EXTERNAL, OpsDefaults.POLARIS_NAMESPACE_EXTERNAL),
                                                                         properties.getValue(POLARIS_TABLE_NAME, OpsDefaults.POLARIS_TABLE_NAME)});
    }

    /** The namespace in which a Data Provider makes its tables, which no project stack may read until it releases them. */
    static final String STAGING = "staging";
    /** The table that a Data Provider's test writes. */
    static final String DATA_PROVIDER_TABLE = "smoke_test";
    /** The table that it also writes and that a Data Provider leaves in staging, which no project stack may read: what the refusal is tested on. */
    static final String DATA_PROVIDER_UNRELEASED_TABLE = "smoke_test_unreleased";

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
