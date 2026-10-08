package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.LogStorage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringComponentTest {

    private final LogStorage s3 = new LogStorage("S3", "s3", """
        type: s3
        bucketNames:
          chunks: logs
        s3:
          region: eu-west-2
        """);

    @Test
    void lokiKeepsItsLogsInTheStorageOfThePlatform() {
        String values = MonitoringComponent.lokiValues(s3);

        assertThat(values).contains("""
              storage:
                type: s3
                bucketNames:
                  chunks: logs
                s3:
                  region: eu-west-2
              schemaConfig:
            """);
        assertThat(values).contains("object_store: s3").contains("so the S3 layout").contains("durable state is in S3.");
    }

    @Test
    void lokiNamesTheStorageServiceOfAnyCloud() {
        String values = MonitoringComponent.lokiValues(new LogStorage("Azure Blob Storage", "azure", "type: azure\n"));

        assertThat(values).contains("type: azure\n  schemaConfig:").contains("object_store: azure")
                          .contains("durable state is in Azure Blob Storage.")
                          .doesNotContain("S3");
    }

    @Test
    void promtailSendsTheClusterNameAsTenant() {
        assertThat(MonitoringComponent.promtailValues("full-emrys-cluster", "S3"))
            .contains("tenant_id: full-emrys-cluster")
            .contains("top-level S3 directory");
        assertThat(MonitoringComponent.grafanaLokiDatasourceValues("full-emrys-cluster"))
            .contains("httpHeaderValue1: full-emrys-cluster");
    }
}
