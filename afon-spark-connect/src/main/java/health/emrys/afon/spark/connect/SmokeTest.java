package health.emrys.afon.spark.connect;

import org.apache.spark.sql.SparkSession;

/**
 * Need to add these JVM args to run:
 * <pre>--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED</pre>
 * and create some port tunelling with:
 * <pre>kubectl port-forward pod/spark-connector-0-driver 15002:15002</pre>
 */
public class SmokeTest {

    public static void main(String[] args) {
        SparkSession spark = SparkSession
                .builder()
                .classic()
                .appName("test2")
                .remote("sc://localhost:15002")
                .getOrCreate();
        spark.read().table("aws.acceptancetests.AwsAccessSpec251117511401").show();
    }

}
