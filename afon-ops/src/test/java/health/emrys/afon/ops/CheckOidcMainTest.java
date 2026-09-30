package health.emrys.afon.ops;

import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckOidcMainTest {

    private final IdentityProvider identityProvider = mock(IdentityProvider.class);
    private final CheckOidcMain checkOidc = new CheckOidcMain(identityProvider, "auth.example.com");

    @Test
    void passesWhenTheIdentityProviderIsReady() {
        when(identityProvider.isReady()).thenReturn(true);
        assertThat(checkOidc.check()).isTrue();
    }

    @Test
    void failsWhenTheIdentityProviderIsNotReady() {
        when(identityProvider.isReady()).thenReturn(false);
        assertThat(checkOidc.check()).isFalse();
    }
}
