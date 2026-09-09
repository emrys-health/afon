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
 * Reads a text file from a public (anonymously readable) S3 bucket over Spark Connect.
 *
 * <p>Five things have to line up for this to work:
 *
 * <ol>
 *   <li>Run kubectl port-forward pod/spark-connector-0-driver 15002:15002</li>
 *   <li>The S3A settings are passed as <em>read options</em>, not as {@code spark.hadoop.*} builder
 *       configs. Over Spark Connect the builder's configs are applied to the remote session at
 *       runtime, but the server's Hadoop {@code Configuration} was built from its own
 *       {@code SparkConf} when its {@code SparkContext} started, so a late
 *       {@code spark.hadoop.fs.s3a.endpoint} never reaches S3A. Read options are layered onto the
 *       Hadoop conf for the individual read (see {@code SessionState.newHadoopConfWithOptions}),
 *       so they must be given <em>without</em> the {@code spark.hadoop.} prefix.</li>
 *   <li>{@code fs.s3a.impl.disable.cache} is on. Hadoop caches a {@code FileSystem} per
 *       scheme+authority and ignores the conf of later lookups, so a long-lived Spark Connect
 *       server that has already touched this bucket would otherwise reuse a {@code FileSystem}
 *       built with the default (non-anonymous) credential chain.</li>
 *   <li>Path-style access is forced. The bucket name contains dots, so the virtual-host form
 *       ({@code s3.carriagereturn.nl.s3.eu-north-1.amazonaws.com}) fails TLS hostname verification
 *       against S3's {@code *.s3.<region>.amazonaws.com} certificate.</li>
 *   <li>The region is stated explicitly, so the AWS SDK does not fall back to its region provider
 *       chain and stall on EC2 instance metadata that a Kubernetes-hosted driver cannot reach.</li>
 * </ol>
 */
public class SmokeSparkConnectRead {

    private static final Logger logger = LoggerFactory.getLogger(SmokeSparkConnectRead.class);

    private static final String REMOTE   = "sc://localhost:15002";
    private static final String REGION   = "eu-north-1";
    private static final String ENDPOINT = "s3." + REGION + ".amazonaws.com";
    private static final String PATH     = "s3a://s3.carriagereturn.nl/hello.txt";

    public Dataset<Row> readText() {
        SparkSession spark = SparkSession
            .builder()
            .connect()
            .remote(REMOTE)
            .getOrCreate();

        logger.info("Reading {} via {}", PATH, REMOTE);
        Dataset<Row> lines = spark.read().options(anonymousS3Options()).text(PATH);
        lines.show(false);
        logger.error(format("Test failed. We should not have been allowed to read %s", PATH));
        return lines;
    }

    private static Map<String, String> anonymousS3Options() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("fs.s3a.impl.disable.cache", "true");
        options.put("fs.s3a.endpoint", ENDPOINT);
        options.put("fs.s3a.endpoint.region", REGION);
        options.put("fs.s3a.path.style.access", "true");
        options.put("fs.s3a.aws.credentials.provider",
                    "org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider");
        return options;
    }

    public static void main(final String[] args) {
        new SmokeSparkConnectRead().readText();
    }
}
