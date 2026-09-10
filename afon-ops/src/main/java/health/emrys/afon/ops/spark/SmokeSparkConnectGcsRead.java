package health.emrys.afon.ops.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.String.format;

/**
 * The GCS counterpart of {@link SmokeSparkConnectRead}: reads a text file from a public
 * (anonymously readable) Google Cloud Storage bucket over Spark Connect. As with the S3 version,
 * the read succeeding is the failure condition.
 *
 * <p>Four things have to line up for this to work:
 *
 * <ol>
 *   <li>Run kubectl port-forward pod/spark-connector-0-driver 15002:15002</li>
 *   <li>The GCS connector ({@code com.google.cloud.bigdataoss:gcs-connector}) is on the driver's
 *       classpath. This is <em>not</em> the same artifact as {@code com.google.cloud:google-cloud-storage},
 *       which is what the {@code spark_jars} image installs; without the connector there is no
 *       {@code GoogleHadoopFileSystem} and the read fails with "No FileSystem for scheme gs"
 *       before any network call is made. See {@code scripts/docker/spark-connect/Dockerfile}.</li>
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
 */
public class SmokeSparkConnectGcsRead {

    private static final Logger logger = LoggerFactory.getLogger(SmokeSparkConnectGcsRead.class);

    private static final String REMOTE = "sc://localhost:15002";
    private static final String PATH   = "gs://dataflow-samples/shakespeare/kinglear.txt";
    private static final String GCS_FILE_SYSTEM =
        "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem";

    public Dataset<Row> readText() {
        SparkSession spark = SparkSession
            .builder()
            .connect()
            .remote(REMOTE)
            .getOrCreate();

        logger.info("Reading {} via {}", PATH, REMOTE);
        Dataset<Row> lines;
        try {
            lines = spark.read().options(anonymousGcsOptions()).text(PATH);
            lines.show(false);
        } catch (Exception e) {
            if (isMissingConnector(e)) {
                // Without the connector the read never leaves the driver, so a refusal here says
                // nothing about whether the cluster is allowed to reach GCS. Treat it as a broken
                // test rather than a pass.
                logger.error(format("Test inconclusive. The driver has no GCS connector, so " +
                                    "reading %s failed on the classpath rather than on access " +
                                    "control. Add com.google.cloud.bigdataoss:gcs-connector to " +
                                    "the Spark image before trusting this check.", PATH), e);
            }
            throw e;
        }
        logger.error(format("Test failed. We should not have been allowed to read %s", PATH));
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
        // gcs-connector >= 2.2.3. Older releases spell this google.cloud.auth.null.enable=true.
        options.put("fs.gs.auth.type", "UNAUTHENTICATED");
        return options;
    }

    public static void main(final String[] args) {
        new SmokeSparkConnectGcsRead().readText();
    }
}
