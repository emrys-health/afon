package health.emrys.afon.spark;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spark settings are logged as they are applied, so the credentials among them must not be, eg the user and password of a catalog.
 */
class SparkWarehouseConfigLoggingTest {

    @Test
    void theCredentialsAreRedactedAndTheOtherSettingsAreLogged() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("spark.sql.catalog.azure.credential", "will:p5ssw0rd");
        config.put("spark.sql.catalog.azure.uri", "http://localhost:49160/api/catalog");
        config.put("spark.sql.catalog.azure.header.X-Iceberg-Access-Delegation", "vended-credentials");
        config.put("spark.hadoop.fs.azure.account.key.account.dfs.core.windows.net", "an-account-key");
        config.put("spark.sql.catalog.azure.token", "a-token");

        String logged = SparkWarehouseConfig.loggable(config);

        assertThat(logged).doesNotContain("p5ssw0rd").doesNotContain("an-account-key").doesNotContain("a-token");
        assertThat(logged).contains("spark.sql.catalog.azure.credential").contains(SparkWarehouseConfig.REDACTED);
        assertThat(logged).contains("http://localhost:49160/api/catalog").contains("vended-credentials");
    }

    @Test
    void theSettingsAreLoggedOneToALine() {
        assertThat(SparkWarehouseConfig.loggable(Map.of("a.b", "c"))).startsWith("\n").contains("a.b").contains("c");
    }
}
