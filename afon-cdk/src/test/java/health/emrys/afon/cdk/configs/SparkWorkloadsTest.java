package health.emrys.afon.cdk.configs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SparkWorkloadsTest {

    @Test
    void sparkConnectServerRegistersNoCatalogsOrCredentials() {
        String command = new SparkWorkloads().startConnectCommand();

        assertThat(command)
            .contains("org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .doesNotContain("spark.sql.catalog.")
            .doesNotContain(".credential")
            .doesNotContain(".token");
    }
}
