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
 * Reads a text file from a public (anonymously readable) S3 bucket over Spark Connect.
 *
 * <p>Five things have to line up for this to work:
 *
 * <ol>
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
 *
 * TODO check that PATH can indeed be seen outside the cluster
 */
public class SparkConnectS3ReadSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectS3ReadSmokeMain.class);

    private static final String REGION = "eu-north-1";
    private static final String ENDPOINT = "s3." + REGION + ".amazonaws.com";
    private static final String PATH     = "s3a://s3.carriagereturn.nl/hello.txt";
    private final KubeClient client;
    private final String namespace;

    private SparkConnectS3ReadSmokeMain(KubeClient client, String namespace) {
        this.client    = client;
        this.namespace = namespace;
    }

    private Dataset<Row> readTextViaPortForward() throws IOException {
        try (PortForwarder forward = new PortForwarder(client, namespace, SPARK_CONNECTOR_POD, 0, 15002)) {
            String remote = "sc://localhost:" + forward.localPort();
            return readText(remote);
        }
    }

    private Dataset<Row> readText(String remote) {
        SparkSession spark = SparkSession
            .builder()
            .connect()
            .remote(remote)
            .getOrCreate();

        logger.info("Reading {} via {}", PATH, remote);
        Dataset<Row> lines = spark.read().options(anonymousS3Options()).text(PATH);
        try {
            lines.show(false);
            String msg = format("Test failed. We should not have been allowed to read %s", PATH);
            logger.error(msg);
            throw new Exception(msg);
        } catch (Exception e) {
            logger.info("Could not read {} so test passes", PATH);
        }
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

    public static void main(final String[] args) throws Exception {
        int exitCode = 0;
        AfonPropertyProvider properties = new FilePropertyProvider(); // please do not change this
        try (KubeClient client = new KubeClient(properties)) {
            new SparkConnectS3ReadSmokeMain(client, KUBERNETES_NAMESPACE).readTextViaPortForward();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
