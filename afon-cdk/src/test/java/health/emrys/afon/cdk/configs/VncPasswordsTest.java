package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each user's desktop is behind their own VNC password, which reaches their pod from their own Secret and Guacamole from its
 * provisioning SQL, so that one user's password cannot open another's desktop.
 */
class VncPasswordsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The JUPYTER_VNC_PASSWORD that {@link SynthesisSupport} configures. */
    private static final String MASTER_KEY = "golden-jupyter-vnc-password";

    /** Two of the users of the template's users.csv. */
    private static final String MAURO = "mauro-talevi";
    private static final String PHIL  = "phil-henry";

    private static JsonNode resources;

    @BeforeAll
    static void synthesise(@TempDir Path dir) throws Exception {
        resources = JSON.readTree(SynthesisSupport.synthesise(StackId.parse("aws-full"), dir)).path("resource");
    }

    @Test
    void derivesAPasswordOfTheLengthVncUses() {
        assertThat(JupyterHubConfig.vncPassword(MASTER_KEY, MAURO))
            .hasSize(JupyterHubConfig.VNC_PASSWORD_LENGTH)
            .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void derivesTheSamePasswordEachTime() {
        assertThat(JupyterHubConfig.vncPassword(MASTER_KEY, MAURO)).isEqualTo(JupyterHubConfig.vncPassword(MASTER_KEY, MAURO));
    }

    @Test
    void derivesADifferentPasswordForEachUser() {
        assertThat(JupyterHubConfig.vncPassword(MASTER_KEY, MAURO)).isNotEqualTo(JupyterHubConfig.vncPassword(MASTER_KEY, PHIL));
    }

    @Test
    void derivesADifferentPasswordForEachKey() {
        assertThat(JupyterHubConfig.vncPassword(MASTER_KEY, MAURO)).isNotEqualTo(JupyterHubConfig.vncPassword("other", MAURO));
    }

    @Test
    void refusesToDeriveWithoutAKey() {
        assertThatThrownBy(() -> JupyterHubConfig.vncPassword("", MAURO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storesEachUsersPasswordInTheirOwnSecret() {
        for (String slug : new String[]{MAURO, PHIL}) {
            JsonNode secret = resources.path("kubernetes_secret").path("vncSecret-" + slug);
            assertThat(secret.path("metadata").path("name").asText()).isEqualTo(JupyterHubConfig.vncSecretName(slug));
            assertThat(secret.path("data").path("password").asText()).isEqualTo(JupyterHubConfig.vncPassword(MASTER_KEY, slug));
        }
    }

    @Test
    void handsEachPodItsUsersSecretByReference() {
        String hubConfig = resources.path("kubernetes_config_map").path("jupyterhubConfigMap")
                                    .path("data").path("jupyterhub_config.py").asText();

        assertThat(hubConfig)
            .contains("c.KubeSpawner.environment = {'JUPYTER_VNC_SESSIONS': '1'}")
            .contains("spawner.environment['JUPYTER_VNC_PASSWORD'] = {\n"
                      + "        'valueFrom': {'secretKeyRef': {'name': 'vnc-password-' + slug, 'key': 'password'}}}")
            .contains("raise web.HTTPError(403, 'No desktop is provisioned for %s' % spawner.user.name)")
            .doesNotContain(MASTER_KEY);
    }

    @Test
    void givesGuacamoleEachUsersOwnPassword() {
        String sql = resources.path("kubernetes_config_map").path("guacamoleProvisionCm")
                              .path("data").path("provision.sql").asText();

        assertThat(sql)
            .contains("(cid, 'password', '" + JupyterHubConfig.vncPassword(MASTER_KEY, MAURO) + "')")
            .contains("(cid, 'password', '" + JupyterHubConfig.vncPassword(MASTER_KEY, PHIL) + "')")
            .doesNotContain(MASTER_KEY);
    }

    @Test
    void doublesTheQuotesOfAnSqlString() {
        assertThat(GuacamoleConfig.sqlString("o'brien@example.com")).isEqualTo("o''brien@example.com");
    }
}
