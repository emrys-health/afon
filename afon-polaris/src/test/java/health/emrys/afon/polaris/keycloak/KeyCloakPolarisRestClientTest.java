package health.emrys.afon.polaris.keycloak;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonProperty.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KeyCloakPolarisRestClientTest {

    private final IdentityProvider identityProvider = mock(IdentityProvider.class);
    private final KeyCloakPolarisRestClient client =
        new KeyCloakPolarisRestClient(properties(), "https", identityProvider);

    private static AfonPropertyProvider properties() {
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        properties.setValue(POLARIS_ENDPOINT, "polaris.example.com");
        properties.setValue(POLARIS_ID, "root");
        properties.setValue(POLARIS_SECRET, "a_secret");
        return properties;
    }

    @Test
    void getsTheAdminClientTokenFromTheIdentityProvider() throws Exception {
        when(identityProvider.clientToken("root", "a_secret")).thenReturn("a_token");
        assertThat(client.getAccessToken()).isEqualTo("a_token");
    }

    @Test
    void doesNotRetryFailuresThatRetryingWillNotFix() throws Exception {
        when(identityProvider.clientToken("root", "a_secret"))
            .thenThrow(new IllegalStateException("Could not obtain token for client root: HTTP 401"));
        assertThatThrownBy(client::getAccessToken).isInstanceOf(IllegalStateException.class);
        verify(identityProvider, times(1)).clientToken("root", "a_secret");
    }
}
