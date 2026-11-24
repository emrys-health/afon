package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that JupyterHub's OAuthenticator redirects sign-out through Pomerium's {@code /.pomerium/sign_out}
 * endpoint so signing out of JupyterHub also ends the Pomerium and Keycloak sessions.
 */
class JupyterHubLogoutTest {

    @Test
    void jupyterHubRedirectsLogoutToPomeriumSignOut(@TempDir Path outDir) throws Exception {
        String plan = SynthesisSupport.synthesise(StackId.parse("aws-full"), outDir);
        JsonNode configMap = new ObjectMapper().readTree(plan)
            .path("resource")
            .path("kubernetes_config_map")
            .path("jupyterhubConfigMap");
        String py = configMap.path("data").path("jupyterhub_config.py").asText();
        assertThat(py).contains("c.GenericOAuthenticator.logout_redirect_url = 'https://jupyter.golden.example/.pomerium/sign_out'");
    }
}
