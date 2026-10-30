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

    @Test
    void sparkConnectWaitsForTheMasterToBeReadyAndFailsIfItIsNot() {
        String command = new SparkWorkloads().waitForMasterCommand();

        assertThat(command)
            .contains("kubectl get pod emrys-master-0")
            .contains("kubectl wait --for=condition=Ready pod/emrys-master-0 --timeout=600s")
            .contains("exit 1")
            // a shell expansion would be read by Terraform as an interpolation
            .doesNotContain("${");
    }
}
