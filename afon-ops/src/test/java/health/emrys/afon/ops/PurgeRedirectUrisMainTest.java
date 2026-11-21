package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.AWS_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.OAUTH2_CLIENT_ID;
import static health.emrys.afon.config.AfonProperty.OAUTH2_CLIENT_SECRET;
import static health.emrys.afon.config.AfonProperty.OIDC_PROJECT_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The redirect URIs that stay on the browser client are the callbacks of the Jupyter of every cloud of the config, and the rest are stale. */
class PurgeRedirectUrisMainTest {

    private final IdentityProvider      identity   = mock(IdentityProvider.class);
    private final AfonPropertyProvider  properties = mock(AfonPropertyProvider.class);
    private final ByteArrayOutputStream output     = new ByteArrayOutputStream();
    private final PurgeRedirectUrisMain purge      = new PurgeRedirectUrisMain(identity, properties, new PrintStream(output));

    @BeforeEach
    void setUp() {
        when(properties.getValue(any(AfonProperty.class), anyString())).thenReturn("");
        when(properties.getValue(OAUTH2_CLIENT_ID)).thenReturn("emrys");
        when(properties.getValue(OAUTH2_CLIENT_SECRET)).thenReturn("secret");
    }

    @Test
    void keepsTheCallbacksOfTheJupyterOfEveryCloudThatHasADomain() {
        when(properties.getValue(AWS_JUPYTER_DOMAIN_NAME, "")).thenReturn("aws-jupyter.example.org");
        when(properties.getValue(AZURE_JUPYTER_DOMAIN_NAME, "")).thenReturn(" azure-jupyter.example.org ");

        assertThat(purge.callbacksToKeep()).containsExactly(
            "https://aws-jupyter.example.org/oauth2/callback", "https://aws-jupyter.example.org/hub/oauth_callback",
            "https://azure-jupyter.example.org/oauth2/callback", "https://azure-jupyter.example.org/hub/oauth_callback");
    }

    @Test
    void takesOffTheRestAndSaysWhich() throws Exception {
        when(properties.getValue(AWS_JUPYTER_DOMAIN_NAME, "")).thenReturn("aws-jupyter.example.org");
        when(identity.purgeBrowserClientRedirectUris(eq("emrys"), eq("secret"), any(), eq(false)))
            .thenReturn(List.of("https://old-jupyter.example.org/oauth2/callback"));

        assertThat(purge.purge(false)).containsExactly("https://old-jupyter.example.org/oauth2/callback");
        assertThat(output.toString()).contains("Took off 1 stale redirect URI", "https://old-jupyter.example.org/oauth2/callback");
    }

    @Test
    void aDryRunOnlySaysWhichWouldBeTakenOff() throws Exception {
        when(properties.getValue(AWS_JUPYTER_DOMAIN_NAME, "")).thenReturn("aws-jupyter.example.org");
        when(identity.purgeBrowserClientRedirectUris(anyString(), anyString(), any(), eq(true)))
            .thenReturn(List.of("https://old-jupyter.example.org/oauth2/callback"));

        purge.purge(true);

        assertThat(output.toString()).contains("Would take off 1 stale redirect URI");
    }

    @Test
    void refusesWhenNoCloudHasAJupyterDomainAsItWouldTakeOffEveryUri() throws Exception {
        assertThatThrownBy(() -> purge.purge(false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("No cloud has a Jupyter domain name");
        verify(identity, never()).purgeBrowserClientRedirectUris(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void leavesAProjectsClientToTheCentralIdentity() throws Exception {
        when(properties.getValue(OIDC_PROJECT_CLAIM, "")).thenReturn("project");
        when(properties.getValue(AWS_JUPYTER_DOMAIN_NAME, "")).thenReturn("aws-jupyter.example.org");

        assertThat(purge.purge(false)).isEmpty();
        verify(identity, never()).purgeBrowserClientRedirectUris(anyString(), anyString(), any(), anyBoolean());
    }
}
