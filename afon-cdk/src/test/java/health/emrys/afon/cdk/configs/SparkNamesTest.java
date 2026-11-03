package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static health.emrys.afon.config.AfonProperty.SPARK_CLUSTER_NAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SparkNamesTest {

    @Test
    void namesAreDerivedFromTheClusterName() {
        SparkNames names = new SparkNames("afon-spark");

        assertThat(names.master()).isEqualTo("afon-spark-master");
        assertThat(names.worker()).isEqualTo("afon-spark-worker");
        assertThat(names.masterPod()).isEqualTo("afon-spark-master-0");
        assertThat(names.masterService()).isEqualTo("afon-spark-master-svc");
    }

    @Test
    void blankClusterNameDefaultsToSpark() {
        AfonPropertyProvider properties = mock(AfonPropertyProvider.class);
        when(properties.getValue(SPARK_CLUSTER_NAME, "")).thenReturn("  ");

        assertThat(SparkNames.from(properties).clusterName()).isEqualTo("spark");
    }

    @Test
    void destroyCommandsFallBackToEmrysForStacksWithoutTheTrigger() {
        assertThat(SparkWorkloads.EXISTING_CLUSTER)
            .isEqualTo("${try(self.triggers.clusterName, \"emrys\")}");
    }

    @Test
    void clusterNameMustBeADnsLabel() {
        assertThatThrownBy(() -> new SparkNames("Afon_Spark")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SparkNames("-spark")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SparkNames("a".repeat(43))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void destroyCommandsUseTheNameTheClusterWasCreatedWith() {
        // exercised through the waiting command, which names the pod
        assertThat(new SparkWorkloads(new SparkNames("afon-spark")).waitForMasterCommand())
            .contains("pod/afon-spark-master-0");
    }
}
