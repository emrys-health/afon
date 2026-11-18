package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cloud of the cluster that Keycloak is in: the stacks' unless the config says it is another's, as a project's does for an identity that is on
 * another cloud.
 */
class KeyCloakTunnelCloudTest {

    @TempDir
    Path dir;

    private FilePropertyProvider properties(String afon) throws IOException {
        Files.writeString(dir.resolve("afon.properties"), afon);
        return new FilePropertyProvider(dir.toString(), java.util.List.of("afon"));
    }

    @Test
    void isTheCloudOfTheStacksUnlessTheConfigNamesAnother() throws IOException {
        assertThat(KeyCloakTunnel.cloudOfKeycloak(AfonCloud.AZURE, properties("ENV_NAME=dev\n"))).isEqualTo(AfonCloud.AZURE);
        assertThat(KeyCloakTunnel.cloudOfKeycloak(AfonCloud.AZURE, properties("KEYCLOAK_CLOUD=\n"))).isEqualTo(AfonCloud.AZURE);
        assertThat(KeyCloakTunnel.cloudOfKeycloak(AfonCloud.AZURE, properties("KEYCLOAK_CLOUD=aws\n"))).isEqualTo(AfonCloud.AWS);
        assertThat(KeyCloakTunnel.cloudOfKeycloak(AfonCloud.AWS, properties("KEYCLOAK_CLOUD=GCP\n"))).isEqualTo(AfonCloud.GCP);
    }

    @Test
    void refusesACloudThatIsNotOne() throws IOException {
        assertThatThrownBy(() -> KeyCloakTunnel.cloudOfKeycloak(AfonCloud.AZURE, properties("KEYCLOAK_CLOUD=mars\n")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("KEYCLOAK_CLOUD is 'mars'");
    }
}
