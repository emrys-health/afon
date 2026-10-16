package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStacksMainTest {

    @Test
    void stacksAreNamedByIdOrByRoleInTheCloud() {
        assertThat(SnapshotStacksMain.named("aws-full")).isEqualTo("aws-full");
        assertThat(SnapshotStacksMain.named("aws-tre")).isEqualTo("aws-full");
        assertThat(SnapshotStacksMain.named("AZURE-Catalog")).isEqualTo("azure-polaris");
    }

    @Test
    void whatIsNotAStackOfTheStacksFileIsLeftAsItIs() {
        // eg the dir of a stack synthesised with another stacks file, or the legacy stack of the cloud
        assertThat(SnapshotStacksMain.named("aws-nonesuch")).isEqualTo("aws-nonesuch");
        assertThat(SnapshotStacksMain.named("gcp-identity")).isEqualTo("gcp-identity");
        assertThat(SnapshotStacksMain.named("aws")).isEqualTo("aws");
    }
}
