package health.emrys.afon.cluster;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonProperties.AfonCloud.*;
import static org.assertj.core.api.Assertions.assertThat;

class PolarisClusterCredentialsTest {

    @Test
    public void testGetAccessTokens() {
        assertThat(PolarisClusterCredentials.createClusterCredentials(AWS).getAccessToken()).isNotEmpty();
        assertThat(PolarisClusterCredentials.createClusterCredentials(AZURE).getAccessToken()).isNotEmpty();
        assertThat(PolarisClusterCredentials.createClusterCredentials(GCP).getAccessToken()).isNotEmpty();
    }
}