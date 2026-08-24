package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.RegisterPolarisTableMain;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Need to add these JVM args to run:
 * <pre>--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED</pre>
 * and run port-forward:
 * <pre>kubectl port-forward pod/spark-connector-0-driver 15002:15002</pre>
 *
 * Note that the `;token=...` string just must match with PreSharedKeyAuthenticationInterceptor
 * (example, sc://localhost:15002/;token=deadbeef)
 * See SPARK-51156 "Provide a basic authentication token when running Spark Connect server locally"
 *
 * Pass the table name as args:
 * <pre>aws.acceptancetests.AwsAccessSpec</pre>
 * <pre>aws_trino.acceptancetests.AwsAccessSpec</pre>
 *
 * You'll need to register the table with class {@link RegisterPolarisTableMain} and args:
 * <pre>
 * "AWS acceptancetests AwsAccessSpec251117511401 s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click"
 * </pre>
 */
public class SparkReadSmoke {

    private static final Logger logger = LoggerFactory.getLogger(SparkReadSmoke.class);

    public void smokeTest(SparkSession spark, String tableName) {
        logger.info("Reading table {}", tableName);
        spark.read().table(tableName).show();
    }

    private static SparkSession createSparkSession() {
        SparkSession spark = SparkSession
                .builder()
                .classic()
                .appName("test2")
                .remote("sc://localhost:15002/;token=deadbeef")
                .getOrCreate();
        return spark;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.printf("%s requires argument: table-name", TrinoQuerySmoke.class.getSimpleName());
            return;
        }
        String tableName = args[0];
        var app = new SparkReadSmoke();
        app.smokeTest(createSparkSession(), tableName);
    }

}
