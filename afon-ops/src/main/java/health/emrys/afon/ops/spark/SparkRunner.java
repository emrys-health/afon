package health.emrys.afon.ops.spark;

import health.emrys.afon.config.*;
import health.emrys.afon.ops.kube.tools.PolarisAccess;
import health.emrys.afon.spark.SparkClient;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.OIDC_PROJECT_CLAIM;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static org.slf4j.LoggerFactory.getLogger;

abstract public class SparkRunner {
    private static final Logger logger = getLogger(SparkRunner.class);

    private AfonPropertyProvider properties;

    protected SparkRunner(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    /**
     * @return whether the config is of a data project stack, that runs a Data Provider's Polaris: it has a project stack id, names no claim for the project in its tokens, as a
     * TRE's does, and has the admin client of its Polaris
     */
    public static boolean isDataProjectStack(AfonPropertyProvider properties) {
        return isSet(properties, PROJECT_ID) && !isSet(properties, OIDC_PROJECT_CLAIM) && isSet(properties, POLARIS_ID);
    }

    private static boolean isSet(AfonPropertyProvider properties, AfonProperty property) {
        return properties.hasProperty(property) && !properties.getValue(property, "").isBlank();
    }

    /**
     * The user that Spark signs in to Polaris as.  In a Data Provider's project it is Polaris' admin client, as its Polaris takes the tokens of the
     * members of the projects that it lets in, with their audience, second factor and project, and this client, which is Polaris' own: the
     * users of {@code users.csv} are not made in the identity by a project.  Elsewhere it is the configured user, as ever.
     */
    static AfonUser userFor(AfonPropertyProvider properties, AfonUserProvider users) {
        if (isDataProjectStack(properties)) {
            return AfonUserProvider.createUser(properties.getValue(POLARIS_ID), properties.getValue(POLARIS_SECRET));
        }
        return users.getConfiguredUser(properties);
    }

    /** What runs with Polaris reached, which may fail. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    /**
     * Runs {@code action} with Polaris reached as this machine can reach a Data Provider's, through a port-forward to its pod, as {@code POLARIS_ACCESS}
     * says (the default), so that this machine need not be among the addresses that its ingress admits, which the project's Polaris is not.  Elsewhere, or
     * when the endpoint is given already ({@code POLARIS_ENDPOINT}, by a caller that has made its own), it runs it as it is: at Polaris' public domain name.
     */
    static void withPolarisAccess(AfonPropertyProvider properties, AfonCloud cloud, Action action) throws Exception {
        if (!isDataProjectStack(properties)) {
            action.run();
            return;
        }
        try (PolarisAccess access = PolarisAccess.open(properties, cloud)) {
            if (access.endpoint() == null || access.endpoint().equals(System.getProperty(POLARIS_ENDPOINT.getName()))) {
                action.run();
                return;
            }
            System.setProperty(POLARIS_ENDPOINT.getName(), access.endpoint());
            try {
                action.run();
            } finally {
                System.clearProperty(POLARIS_ENDPOINT.getName());
            }
        }
    }

    public static void parseAndExecute(SparkRunner app, AfonPropertyProvider properties, AfonUserProvider users, String[] args) {
        try {
            if (args.length < 4) {
                System.out.printf("""
                                      SparkRunner requires at least 4 arguments:
                                          cloud catalog-name namespace table-name [values-csv] [username] [password]
                                      values-csv: csv list of values to read and write
                                      args: %s    
                                      """, String.join(" ", args));
                return;
            }
            AfonCloud          cloud       = AfonCloud.valueOf(args[0].toUpperCase());
            String             catalogName = args[1];
            String             namespace   = args[2];
            String             tableName   = args[3];
            String             values;
            final List<String> lines       = new ArrayList<>();
            if (args.length == 5) {
                values = args[4];
                Arrays.stream(values.split(",")).forEach(lines::add);
            } else {
                Audit audit = new Audit(properties);
                lines.addAll(audit.configuration());
            }
            AfonUser user;
            if (args.length == 7) {
                user = AfonUserProvider.createUser(args[5], args[6]);
            } else {
                user = userFor(properties, users);
            }
            withPolarisAccess(properties, cloud, () -> {
                SparkSession spark = new SparkClient(cloud, properties, user).sparkSession(catalogName);
                app.doRun(spark, catalogName, namespace, tableName, lines);
            });
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    protected abstract void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values);


}
