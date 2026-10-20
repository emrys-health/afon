package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static health.emrys.afon.ops.smoke.config.SmokeConfig.KUBERNETES_NAMESPACE;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.SPARK_CONNECTOR_POD;
import static java.lang.String.format;

/**
 * The GCS counterpart of {@link SparkConnectS3ReadSmokeMain}: reads a text file from a public
 * (anonymously readable) Google Cloud Storage bucket over Spark Connect. As with the S3 version,
 * the read succeeding is the failure condition.
 *
 * <p>Things have to line up for this to work:
 *
 * <ol>
 *   <li>Run kubectl port-forward pod/spark-connector-0-driver 15002:15002</li>
 *   <li>The GCS settings are passed as <em>read options</em>, not as {@code spark.hadoop.*} builder
 *       configs. Over Spark Connect the builder's configs are applied to the remote session at
 *       runtime, but the server's Hadoop {@code Configuration} was built from its own
 *       {@code SparkConf} when its {@code SparkContext} started, so a late
 *       {@code spark.hadoop.fs.gs.auth.type} never reaches the connector. Read options are layered
 *       onto the Hadoop conf for the individual read (see
 *       {@code SessionState.newHadoopConfWithOptions}), so they must be given <em>without</em> the
 *       {@code spark.hadoop.} prefix.</li>
 *   <li>{@code fs.gs.impl.disable.cache} is on. Hadoop caches a {@code FileSystem} per
 *       scheme+authority and ignores the conf of later lookups, so a long-lived Spark Connect
 *       server that has already touched this bucket would otherwise reuse a {@code FileSystem}
 *       built with the default (service-account) credentials.</li>
 * </ol>
 *
 * <p>The S3 version's remaining two settings have no GCS equivalent: path-style addressing is an S3
 * concept, and GCS is served from a single global endpoint, so there is no region for the client to
 * go looking for on the instance metadata service.
 *
 * TODO check that PATH can indeed be seen outside the cluster
 */
public class SparkConnectGcsReadSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectGcsReadSmokeMain.class);

    private static final String     PATH            = "gs://dataflow-samples/shakespeare/kinglear.txt";
    private static final String     GCS_FILE_SYSTEM =
        "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem";
    private final        KubeClient client;
    private final        String     namespace;

    private SparkConnectGcsReadSmokeMain(KubeClient client, String namespace) {
        this.client    = client;
        this.namespace = namespace;
    }

    public Dataset<Row> readTextThroughPortForwarder() throws IOException {
        try (PortForwarder forward = new PortForwarder(client, namespace, SPARK_CONNECTOR_POD, 0, 15002)) {
            String remote = "sc://localhost:" + forward.localPort();
            return readText(remote);
        }
    }

    public Dataset<Row> readText(String remote) {
        SparkSession spark = SparkSession
            .builder()
            .connect()
            .remote(remote)
            .getOrCreate();

        logger.info("Reading {} via {}", PATH, remote);
        Dataset<Row> lines = null;
        try {
            lines = spark.read().options(anonymousGcsOptions()).text(PATH);
            lines.show(false);
        } catch (Exception e) {
            if (isMissingConnector(e)) {
                logger.warn(format("The driver has no GCS connector, so " +
                                       "reading %s failed due to the classpath rather than access " +
                                       "control. If this is because downloading packages is disabled, everything is OK",
                                   PATH), e);
            }
            lines = null;
        }

        return lines;
    }

    private static boolean isMissingConnector(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof ClassNotFoundException
                || (message != null && (message.contains(GCS_FILE_SYSTEM)
                || message.contains("No FileSystem for scheme")))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> anonymousGcsOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("fs.gs.impl.disable.cache", "true");
        options.put("fs.gs.impl", GCS_FILE_SYSTEM);
        options.put("fs.AbstractFileSystem.gs.impl", "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFS");
        options.put("spark.jars.packages",
                    "com.google.cloud.bigdataoss:gcs-connector:4.0.5");
        // gcs-connector >= 2.2.3. Older releases spell this google.cloud.auth.null.enable=true.
        options.put("fs.gs.auth.type", "UNAUTHENTICATED");
        return options;
    }

    public static void main(final String[] args) {
        AfonPropertyProvider properties = new FilePropertyProvider(); // please do not change this
        int exitCode = 0;
        try (KubeClient client = new KubeClient(properties)) {
            SparkConnectGcsReadSmokeMain app = new SparkConnectGcsReadSmokeMain(client, KUBERNETES_NAMESPACE);
            Dataset<Row>                 df  = app.readTextThroughPortForwarder();
            if (df != null) {
                String                   msg = format("Test failed. We should not have been allowed to read public bucket %s", PATH);
                logger.error(msg);
                throw new Exception(msg);
            }
            logger.info("Test complete. We could not talk to {}", PATH);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
