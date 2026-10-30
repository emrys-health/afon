package health.emrys.afon.spark.audit;

import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AuditQueryListenerTest {

    /** Registered by class name, as on the cluster, so it shares what it logs statically. */
    public static class RecordingListener extends AuditQueryListener {
        static final Queue<String> records = new ConcurrentLinkedQueue<>();

        @Override
        protected void log(String record) {
            records.add(record);
        }
    }

    private static SparkSession spark;

    @BeforeAll
    static void startSpark() throws Exception {
        spark = SparkSession.builder()
                            .master("local[1]")
                            .config("spark.ui.enabled", "false")
                            .config("spark.sql.queryExecutionListeners", RecordingListener.class.getName())
                            .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                            .config("spark.sql.catalog.polaris", AuditQueryListener.ICEBERG_CATALOG)
                            .config("spark.sql.catalog.polaris.type", "hadoop")
                            .config("spark.sql.catalog.polaris.warehouse", Files.createTempDirectory("audit").toString())
                            .config("spark.sql.catalog.polaris.token", jwt("{\"sub\":\"1234\",\"preferred_username\":\"alice\"}"))
                            .getOrCreate();
        spark.sql("CREATE TABLE polaris.ns.patients (id INT, name STRING) USING iceberg");
    }

    @AfterAll
    static void stopSpark() {
        spark.stop();
    }

    @BeforeEach
    void forgetRecords() throws Exception {
        spark.sparkContext().listenerBus().waitUntilEmpty();
        RecordingListener.records.clear();
    }

    @Test
    void sqlIsLoggedWithTheUserOfTheToken() {
        spark.sql("SELECT name\nFROM polaris.ns.patients WHERE id = 1").collect();

        assertThat(awaitRecord())
            .contains("user=alice")
            .contains("tables=polaris.ns.patients")
            .contains("status=SUCCEEDED")
            .endsWith("sql=SELECT name FROM polaris.ns.patients WHERE id = 1");
    }

    @Test
    void dataFrameWriteIsLoggedWithoutItsData() throws Exception {
        spark.createDataset(List.of("secret"), Encoders.STRING())
             .selectExpr("42 AS id", "value AS name")
             .writeTo("polaris.ns.patients")
             .append();

        assertThat(awaitRecord())
            .contains("user=alice")
            .contains("tables=polaris.ns.patients")
            .contains("operation=AppendData")
            .doesNotContain("sql=")
            .doesNotContain("secret");
    }

    @Test
    void queriesOfNoIcebergTableAreNotLogged() {
        spark.sql("SELECT 1").collect();
        spark.sql("SELECT * FROM polaris.ns.patients").collect();

        assertThat(awaitRecord()).contains("sql=SELECT * FROM polaris.ns.patients");
        assertThat(RecordingListener.records).isEmpty();
    }

    @Test
    void usernameFallsBackToSubject() {
        assertThat(AuditQueryListener.username(jwt("{\"sub\":\"1234\"}"))).isEqualTo("1234");
        assertThat(AuditQueryListener.username("not a jwt")).isEqualTo("unreadable-token");
    }

    private static String awaitRecord() {
        await().atMost(10, SECONDS).until(() -> !RecordingListener.records.isEmpty());
        return RecordingListener.records.poll();
    }

    private static String jwt(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
               + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
