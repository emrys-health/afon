package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonProperty.OIDC_PROJECT_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;

class JupyterMfaConfigTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    @Test
    void theGateOfAProjectsTreAsksForPkce() {
        properties.setValue(OIDC_PROJECT_CLAIM, "project");

        assertThat(JupyterMfaConfig.pkceArgs(properties)).containsExactly("--code-challenge-method=S256");
    }

    @Test
    void theGateOfAStackOutsideAProjectAsksForNone() {
        // the config template has the key empty or absent, which a blank value is as well
        assertThat(JupyterMfaConfig.pkceArgs(properties)).isEmpty();
        properties.setValue(OIDC_PROJECT_CLAIM, "  ");
        assertThat(JupyterMfaConfig.pkceArgs(properties)).isEmpty();
    }
}
