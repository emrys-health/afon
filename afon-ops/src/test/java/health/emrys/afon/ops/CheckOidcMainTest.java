package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckOidcMainTest {

    private final IdentityProvider identityProvider = mock(IdentityProvider.class);
    private final AfonPropertyProvider properties = mock(AfonPropertyProvider.class);
    private final AfonUserProvider userProvider = mock(AfonUserProvider.class);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final CheckOidcMain checkOidc = new CheckOidcMain(identityProvider, "auth.example.com",
            properties, userProvider, new PrintStream(out, true), new PrintStream(err, true));

    @Test
    void passesWhenTheIdentityProviderIsReady() {
        when(identityProvider.isReady()).thenReturn(true);
        assertThat(checkOidc.check()).isTrue();
        assertThat(out.toString()).contains("OIDC provider at 'auth.example.com' is ready");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void failsWhenTheIdentityProviderIsNotReady() {
        when(identityProvider.isReady()).thenReturn(false);
        assertThat(checkOidc.check()).isFalse();
        assertThat(err.toString()).contains("OIDC provider at 'auth.example.com' is not ready");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void showsTheAdminToken() throws Exception {
        when(properties.getValue(AfonProperty.POLARIS_ID)).thenReturn("admin");
        when(properties.getValue(AfonProperty.POLARIS_SECRET)).thenReturn("s3cret");
        when(identityProvider.clientToken("admin", "s3cret")).thenReturn("tok");
        checkOidc.adminToken(AfonCloud.AWS);
        assertThat(out.toString()).contains("Access token for polarisId 'admin' on cloud 'aws': tok");
    }

    @Test
    void showsTheUserToken() throws Exception {
        when(userProvider.getConfiguredUser(properties)).thenReturn(new AfonUser("bob", "pw", "bob@example.com", "bob", "Bob", "Smith"));
        when(identityProvider.userToken("bob", "pw")).thenReturn("tok");
        checkOidc.userToken(AfonCloud.AWS, "user");
        assertThat(out.toString()).contains("Access token for user 'bob' on cloud 'aws': tok");
    }

    @Test
    void showsTheTokenOfANamedUser() throws Exception {
        when(userProvider.getUser("mauro")).thenReturn(new AfonUser("mauro", "pw", "m@example.com", "mauro", "M", "T"));
        when(identityProvider.userToken("mauro", "pw")).thenReturn("tok");
        checkOidc.userToken(AfonCloud.AWS, "mauro");
        assertThat(out.toString()).contains("Access token for user 'mauro' on cloud 'aws': tok");
    }
}
