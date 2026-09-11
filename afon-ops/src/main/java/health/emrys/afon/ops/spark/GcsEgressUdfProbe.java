package health.emrys.afon.ops.spark;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.apache.spark.sql.functions.callUDF;
import static org.apache.spark.sql.functions.lit;

/**
 * Companion to {@link SmokeSparkConnectGcsRead}, probing the routes that {@code gs://} does not
 * cover. {@link SmokeSparkConnectGcsRead} only ever exercises Spark's Hadoop {@code FileSystem}
 * layer, which is dead on this cluster because the image ships {@code google-cloud-storage} (the
 * client library) but not {@code gcs-connector} (the Hadoop connector), and an uploaded connector
 * jar does not register the {@code gs} scheme anyway (Hadoop resolves {@code FileSystem} impls
 * through the {@code Configuration} classloader, not the Connect session's artifact classloader).
 *
 * <p>That closes one door. This probe checks whether the room has other doors: <em>arbitrary code
 * running on the cluster</em>, which is exactly what a user-defined function or a notebook cell is.
 * Neither of the routes below touches {@code gs://}; both depend only on the driver being able to
 * open an outbound HTTPS connection to {@code storage.googleapis.com}, which the Cilium egress
 * policy allows by FQDN (and, unlike S3's per-bucket virtual-host names, that single host fronts
 * every public bucket on the internet).
 *
 * <ol>
 *   <li><b>Plain HTTPS.</b> {@link #httpsFirstLine(String)} opens {@code java.net.URL} against the
 *       JSON download endpoint. Nothing Spark- or Google-specific; any JVM code on the cluster can
 *       do this.</li>
 *   <li><b>The GCS client library.</b> {@link #gcsClientFirstLine(String, String)} uses
 *       {@code com.google.cloud:google-cloud-storage} — already on the driver classpath — with
 *       explicitly anonymous credentials, i.e. the SDK path a user would reach for.</li>
 * </ol>
 *
 * <p>Both are wrapped in Spark UDFs and executed <em>through Spark Connect</em> so the read happens
 * on the cluster, not on this client. The Connect client auto-ships the UDF's classes to the
 * server; run {@code kubectl port-forward pod/spark-connector-0-driver 15002:15002} first.
 *
 * <p>Interpreting the result: this is a <em>negative</em> security test, so a successful read is a
 * finding, not a success. If either UDF returns the first line of the public object, the cluster's
 * code-execution surface can reach public GCS regardless of the missing Hadoop connector, and the
 * connector's absence is obscurity rather than a control. A network-level failure
 * ({@code UnknownHostException} / connection refused / timeout) from <em>both</em> UDFs is what a
 * genuinely closed egress boundary looks like.
 */
public class GcsEgressUdfProbe {

    private static final Logger logger = LoggerFactory.getLogger(GcsEgressUdfProbe.class);

    private static final String REMOTE = "sc://localhost:15002";

    /** A world-readable object in Google's public {@code dataflow-samples} bucket. */
    private static final String BUCKET = "dataflow-samples";
    private static final String OBJECT = "shakespeare/kinglear.txt";

    public void probe() {
        SparkSession spark = SparkSession
            .builder()
            .connect()
            .remote(REMOTE)
            .getOrCreate();

        runUdf(spark, "gcs_probe_https", (UDF1<String, String>) GcsEgressUdfProbe::httpsFirstLine,
               "https://storage.googleapis.com/" + BUCKET + "/" + OBJECT);

        runUdf(spark, "gcs_probe_client", (UDF1<String, String>) GcsEgressUdfProbe::gcsClientFirstLine,
               BUCKET + "/" + OBJECT);
    }

    private void runUdf(SparkSession spark, String name, UDF1<String, String> udf, String argument) {
        spark.udf().register(name, udf, DataTypes.StringType);
        logger.info("Probing GCS egress via {} with argument {}", name, argument);

        // A one-row frame whose single column is the UDF applied to the argument, evaluated on the
        // cluster. .collect() forces execution and brings back whatever the cluster saw.
        Dataset<Row> result = spark.range(1).select(callUDF(name, lit(argument)).as("first_line"));
        String firstLine = result.first().getString(0);

        if (firstLine != null && firstLine.startsWith("ERR:")) {
            logger.info("{}: blocked on the cluster -> {}", name, firstLine.substring(4).trim());
        } else {
            logger.error("SECURITY FINDING: {} read public GCS from the cluster. First line: {}",
                         name, firstLine);
        }
    }

    /**
     * Route 1 — plain HTTPS. Runs on the cluster inside the UDF. Returns the first line of the
     * object, or an {@code ERR:} sentinel so a network failure is reported as data rather than
     * blowing up the whole Spark job.
     */
    public static String httpsFirstLine(String url) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(URI.create(url).toURL().openStream(), StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Exception e) {
            return "ERR: " + e;
        }
    }

    /**
     * Route 2 — the {@code google-cloud-storage} client already on the driver classpath, with
     * explicitly anonymous credentials. {@code arg} is {@code <bucket>/<object>}.
     */
    public static String gcsClientFirstLine(String arg) {
        int slash = arg.indexOf('/');
        String bucket = arg.substring(0, slash);
        String object = arg.substring(slash + 1);
        try {
            Storage storage = StorageOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
            byte[] content = storage.readAllBytes(BlobId.of(bucket, object));
            String text = new String(content, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline);
        } catch (Exception e) {
            return "ERR: " + e;
        }
    }

    public static void main(final String[] args) {
        new GcsEgressUdfProbe().probe();
    }
}
