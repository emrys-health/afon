package health.emrys.afon.cluster;

import org.junit.Ignore;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonProperties.AfonCloud.*;
import static org.assertj.core.api.Assertions.assertThat;

@Ignore
class PolarisClusterCredentialsTest {

    /**
     * This is not a unit test but makes a call to the cluster and fails if the cluster is down.
     */
    @Ignore
    public void testGetAccessTokens() {
        assertThat(PolarisClusterCredentials.createClusterCredentials(AWS).getAccessToken()).isNotEmpty();
        assertThat(PolarisClusterCredentials.createClusterCredentials(AZURE).getAccessToken()).isNotEmpty();
        assertThat(PolarisClusterCredentials.createClusterCredentials(GCP).getAccessToken()).isNotEmpty();
    }
}