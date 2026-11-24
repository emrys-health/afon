package health.emrys.afon.spark.guard;

import org.apache.spark.sql.SparkSession;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Through a session of Spark's own, with the extension registered as the Spark Connect server registers it. */
class PlanPolicyTest {

    private static SparkSession spark;

    @BeforeAll
    static void start() {
        spark = SparkSession.builder()
                            .master("local[1]")
                            .appName("PlanPolicyTest")
                            .config("spark.ui.enabled", "false")
                            .config("spark.sql.extensions", SparkGuardExtensions.class.getName())
                            .getOrCreate();
    }

    @AfterAll
    static void stop() {
        spark.stop();
    }

    private static void assertRefused(ThrowableAssert.ThrowingCallable query, String why) {
        assertThatThrownBy(query).hasStackTraceContaining("SecurityException").hasStackTraceContaining(why);
    }

    @Test
    void anOrdinaryQueryAndATemporaryViewAreLetThrough() {
        spark.range(3).createOrReplaceTempView("numbers");

        assertThat(spark.sql("SELECT count(*) FROM numbers WHERE id > 0").first().getLong(0)).isEqualTo(2L);
    }

    @Test
    void addingAJarAFileOrAnArchiveIsRefused() {
        assertRefused(() -> spark.sql("ADD JAR /tmp/evil.jar"), "ADD JAR");
        assertRefused(() -> spark.sql("ADD FILE /tmp/evil.txt"), "ADD JAR");
        assertRefused(() -> spark.sql("ADD ARCHIVE /tmp/evil.zip"), "ADD JAR");
    }

    @Test
    void sqlBuiltAtRunTimeIsCheckedAsWell() {
        spark.sql("DECLARE OR REPLACE VARIABLE statement STRING DEFAULT 'ADD ' || 'JAR /tmp/evil.jar'");

        assertRefused(() -> spark.sql("EXECUTE IMMEDIATE statement"), "ADD JAR");
    }

    @Test
    void aFunctionFromAClassIsRefused() {
        assertRefused(() -> spark.sql("CREATE TEMPORARY FUNCTION evil AS 'com.example.Evil' USING JAR '/tmp/evil.jar'"), "CREATE FUNCTION");
        assertRefused(() -> spark.sql("CREATE TEMPORARY FUNCTION evil AS 'com.example.Evil'"), "CREATE FUNCTION");
    }

    @Test
    void aFunctionWrittenInSqlIsLetThrough() {
        spark.sql("CREATE OR REPLACE TEMPORARY FUNCTION plus_one(x INT) RETURNS INT RETURN x + 1");

        assertThat(spark.sql("SELECT plus_one(1)").first().getInt(0)).isEqualTo(2);
    }

    @Test
    void aGlobalTemporaryViewIsRefusedHoweverItIsMade() {
        assertRefused(() -> spark.range(1).createGlobalTempView("shared"), "global temporary views");
        assertRefused(() -> spark.range(1).createOrReplaceGlobalTempView("shared"), "global temporary views");
        assertRefused(() -> spark.sql("CREATE GLOBAL TEMPORARY VIEW shared AS SELECT 1"), "global temporary views");
    }

    @Test
    void reflectionIsRefusedInSqlAndInADataFrame() {
        assertRefused(() -> spark.sql("SELECT reflect('java.lang.System', 'getProperty', 'user.home')"), "reflect");
        assertRefused(() -> spark.sql("SELECT java_method('java.lang.System', 'getProperty', 'user.home')"), "reflect");
        assertRefused(() -> spark.sql("SELECT try_reflect('java.lang.System', 'getProperty', 'user.home')"), "reflect");
        assertRefused(() -> spark.range(1).selectExpr("reflect('java.lang.System', 'getProperty', 'user.home')"), "reflect");
        assertRefused(() -> spark.sql("SELECT * FROM (SELECT 1 AS x) WHERE EXISTS (SELECT reflect('java.lang.System', 'getProperty', 'user.home'))"),
                      "reflect");
    }

    @Test
    void aLocalFileIsNotReadHoweverItIsNamed(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("secret.txt"), "secret");
        String path = file.toAbsolutePath().toString();

        assertRefused(() -> spark.read().text(path), "only the object stores");
        assertRefused(() -> spark.read().text(file.toUri().toString()), "only the object stores");
        assertRefused(() -> spark.read().format("text").load(path), "only the object stores");
        assertRefused(() -> spark.sql("SELECT * FROM text.`" + path + "`"), "only the object stores");
        assertRefused(() -> spark.sql("CREATE OR REPLACE TEMPORARY VIEW secret USING text OPTIONS (path '" + path + "')"),
                      "only the object stores");
    }

    @Test
    void nothingIsWrittenToTheLocalDisk(@TempDir Path dir) {
        Path out = dir.resolve("out");

        assertRefused(() -> spark.range(1).write().parquet(out.toString()), "only the object stores");
        assertRefused(() -> spark.range(1).write().format("text").save(out.toUri().toString()), "only the object stores");
        assertRefused(() -> spark.sql("INSERT OVERWRITE DIRECTORY '" + out + "' USING parquet SELECT 1"), "only the object stores");
        assertThat(out).doesNotExist();
    }

    @Test
    void noTableIsMadeInSparkCatalogWhichEverySessionShares() {
        assertRefused(() -> spark.range(1).write().saveAsTable("shared_table"), "spark_catalog");
        assertRefused(() -> spark.sql("CREATE TABLE shared_table (x INT) USING parquet"), "spark_catalog");
        assertRefused(() -> spark.sql("CREATE TABLE shared_table USING parquet AS SELECT 1 AS x"), "spark_catalog");
        assertRefused(() -> spark.sql("CREATE TABLE shared_table (x INT) USING parquet LOCATION '/tmp/shared_table'"), "only the object stores");
        assertThat(spark.catalog().tableExists("shared_table")).isFalse();
    }
}
