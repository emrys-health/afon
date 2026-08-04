package health.emrys.afon.ops.smoke;

import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Need to add these JVM args to run:
 * <pre>--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED</pre>
 * and create some port tunelling with:
 * <pre>kubectl port-forward pod/spark-connector-0-driver 15002:15002</pre>
 */
public class SparkConnectionSmoke {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectionSmoke.class);

    /**
     * Note that the `;token=...` string just must match with PreSharedKeyAuthenticationInterceptor
     * (example, sc://localhost:15002/;token=deadbeef)
     * See SPARK-51156 "Provide a basic authentication token when running Spark Connect server locally"
     *
     * You'll need to register the table with:
     * <pre>
    ./afon.sh -a exec --exec-class ops.RegisterPolarisTableMain --exec-args "AWS acceptancetests AwsAccessSpec251117511401 s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click"
     * </pre>
     */
    public static void main(String[] args) {
        SparkSession spark = SparkSession
                .builder()
                .classic()
                .appName("test2")
                .remote("sc://localhost:15002/;token=deadbeef")
                .getOrCreate();
        String tableName = "aws.acceptancetests.AwsAccessSpec"; // aws_trino.acceptancetests.AwsAccessSpec
        logger.info("Reading table {}", tableName);
        spark.read().table(tableName).show();
    }

}
