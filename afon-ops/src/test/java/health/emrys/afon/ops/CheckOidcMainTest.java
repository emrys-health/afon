package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.ops.project.SmokeMemberCredentials;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(identityProvider.clientToken("bob", "pw")).thenReturn("tok");
        checkOidc.userToken(AfonCloud.AWS, "user");
        assertThat(out.toString()).contains("Access token for user 'bob' on cloud 'aws': tok");
    }

    @Test
    void showsTheTokenOfANamedUser() throws Exception {
        when(userProvider.getUser("mauro")).thenReturn(new AfonUser("mauro", "pw", "m@example.com", "mauro", "M", "T"));
        when(identityProvider.clientToken("mauro", "pw")).thenReturn("tok");
        checkOidc.userToken(AfonCloud.AWS, "mauro");
        assertThat(out.toString()).contains("Access token for user 'mauro' on cloud 'aws': tok");
    }

    private final KeyCloakMemberLogin login = mock(KeyCloakMemberLogin.class);
    @TempDir
    Path home;

    private CheckOidcMain asAProject() throws Exception {
        Path secrets = home.resolve("projects/cvd-2026/secrets");
        Files.createDirectories(secrets);
        Files.writeString(secrets.resolve("tre.secret"), "client-secret\n");
        when(properties.getValue(AfonProperty.PROJECT_ID, "")).thenReturn("cvd-2026");
        when(properties.getValue(AfonProperty.SMOKE_MEMBER, "")).thenReturn("smoke@example.org");
        when(properties.getValue(AfonProperty.OIDC_AUDIENCE)).thenReturn("cvd-2026-tre");
        when(properties.getOidcIssuerUrl(AfonCloud.AWS)).thenReturn("https://idp.example/realms/polaris");
        when(properties.getCloudValue(AfonCloud.AWS, AfonCloudProperty.JUPYTER_DOMAIN_NAME, "")).thenReturn("jupyter.example");
        return new CheckOidcMain(identityProvider, "auth.example.com", properties, userProvider, new PrintStream(out, true), new PrintStream(err, true), home, realm -> login);
    }

    @Test
    void showsTheTokenOfTheSmokeMemberSignedInAsAMemberIs() throws Exception {
        CheckOidcMain project = asAProject();
        new SmokeMemberCredentials("smoke@example.org", "pw", "TOTP").write(home.resolve("projects/cvd-2026/secrets"));
        when(login.login("cvd-2026-tre", "client-secret", "https://jupyter.example/hub/oauth_callback", "smoke@example.org", "pw", "TOTP"))
            .thenReturn(new KeyCloakMemberLogin.Token("the-jwt", new ObjectMapper().createObjectNode(), "TOTP"));

        project.smokeMemberToken(AfonCloud.AWS);

        assertThat(out.toString()).contains("Access token for smoke member 'smoke@example.org' on cloud 'aws': the-jwt");
    }

    @Test
    void saysWhatToDoWhenTheMemberHasNoCredentialsOrThereIsNoSmokeMember() throws Exception {
        CheckOidcMain project = asAProject();
        assertThatThrownBy(() -> project.smokeMemberToken(AfonCloud.AWS)).isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("No credentials for smoke@example.org").hasMessageContaining("smoke-credentials");

        when(properties.getValue(AfonProperty.SMOKE_MEMBER, "")).thenReturn("");
        assertThatThrownBy(() -> project.smokeMemberToken(AfonCloud.AWS)).isInstanceOf(java.io.IOException.class).hasMessageContaining("There is no smoke member");
    }
}
