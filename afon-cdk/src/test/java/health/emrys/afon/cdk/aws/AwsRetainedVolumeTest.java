package health.emrys.afon.cdk.aws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsRetainedVolumeTest {

    @Test
    void sizesVolumesInWholeGib() {
        assertThat(AwsRetainedVolume.gib("10Gi")).isEqualTo(10);
        assertThat(AwsRetainedVolume.gib(" 200Gi ")).isEqualTo(200);
    }

    @Test
    void rejectsSizesThatAreNotWholeGib() {
        for (String size : new String[]{"10G", "512Mi", "1.5Gi", "10"}) {
            assertThatThrownBy(() -> AwsRetainedVolume.gib(size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(size);
        }
    }

    @Test
    void namesTheResourcesSoThatTheImportCanAddressThem() {
        assertThat(AwsRetainedVolume.logicalId("gitea")).isEqualTo("retained_gitea_volume");
    }
}
