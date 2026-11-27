package health.emrys.afon.ops.smoke.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.ops.project.SmokeMemberCredentials;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmokeUserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;
    private final AfonPropertyProvider properties = mock(AfonPropertyProvider.class);
    private final KeyCloakMemberLogin login = mock(KeyCloakMemberLogin.class);

    private void asAProject() throws Exception {
        Path secrets = home.resolve("projects/cvd-2026/secrets");
        Files.createDirectories(secrets);
        Files.writeString(secrets.resolve("tre.secret"), "client-secret\n");
        when(properties.getValue(AfonProperty.PROJECT_ID, "")).thenReturn("cvd-2026");
        when(properties.getValue(AfonProperty.SMOKE_MEMBER, "")).thenReturn("smoke@example.org");
        when(properties.getValue(AfonProperty.OIDC_AUDIENCE)).thenReturn("cvd-2026-tre");
        when(properties.getOidcIssuerUrl(AfonCloud.AWS)).thenReturn("https://idp.example/realms/polaris");
        when(properties.getCloudValue(AfonCloud.AWS, AfonCloudProperty.JUPYTER_DOMAIN_NAME, "")).thenReturn("jupyter.example");
    }

    @Test
    void theSmokeMemberIsKnownByThePolarisPrincipalOfTheirTokenAndHasTheTokenOfTheirSignIn() throws Exception {
        asAProject();
        new SmokeMemberCredentials("smoke@example.org", "pw", "TOTP").write(home.resolve("projects/cvd-2026/secrets"));
        when(login.login("cvd-2026-tre", "client-secret", "https://jupyter.example/hub/oauth_callback", "smoke@example.org", "pw", "TOTP"))
            .thenReturn(new KeyCloakMemberLogin.Token("the-jwt", JSON.readTree("{\"polaris_principal\":\"afon:1234\"}"), "TOTP"));

        SmokeUser user = SmokeUser.ofMember(properties, AfonCloud.AWS, home, realm -> login);

        assertThat(user.username()).isEqualTo("smoke@example.org");
        assertThat(user.principal()).isEqualTo("afon:1234");
        assertThat(user.token()).isEqualTo("the-jwt");
        assertThat(user.isMember()).isTrue();
    }

    @Test
    void theSmokeMemberIsKnownByTheirEmailWhenTheTokenNamesNoPrincipal() throws Exception {
        asAProject();
        new SmokeMemberCredentials("smoke@example.org", "pw", "TOTP").write(home.resolve("projects/cvd-2026/secrets"));
        when(login.login(any(), any(), any(), any(), any(), eq("TOTP"))).thenReturn(new KeyCloakMemberLogin.Token("jwt", JSON.readTree("{}"), "TOTP"));

        assertThat(SmokeUser.ofMember(properties, AfonCloud.AWS, home, realm -> login).principal()).isEqualTo("smoke@example.org");
    }

    @Test
    void saysWhatToDoWithNoSmokeMemberNoCredentialsOrNoJupyterDomain() throws Exception {
        asAProject();
        assertThatThrownBy(() -> SmokeUser.ofMember(properties, AfonCloud.AWS, home, realm -> login)).isInstanceOf(IOException.class)
            .hasMessageContaining("No credentials for smoke@example.org").hasMessageContaining("smoke-credentials");

        new SmokeMemberCredentials("smoke@example.org", "pw", "TOTP").write(home.resolve("projects/cvd-2026/secrets"));
        when(properties.getCloudValue(AfonCloud.AWS, AfonCloudProperty.JUPYTER_DOMAIN_NAME, "")).thenReturn("");
        assertThatThrownBy(() -> SmokeUser.ofMember(properties, AfonCloud.AWS, home, realm -> login)).isInstanceOf(IOException.class)
            .hasMessageContaining("JUPYTER_DOMAIN_NAME is not set");

        when(properties.getValue(AfonProperty.SMOKE_MEMBER, "")).thenReturn("");
        assertThatThrownBy(() -> SmokeUser.ofMember(properties, AfonCloud.AWS, home, realm -> login)).isInstanceOf(IOException.class)
            .hasMessageContaining("There is no smoke member");
    }

    @Test
    void theConfiguredUserIsKnownByTheirUsernameAndHasTheTokenOfTheirClient() throws Exception {
        AfonUserProvider users = mock(AfonUserProvider.class);
        AfonUser admin = new AfonUser("phil", "pw", "phil@example.org", "phil", "Phil", "H");
        when(users.getConfiguredUser(properties)).thenReturn(admin);

        SmokeUser user = SmokeUser.ofConfiguredUser(properties, users, (props, u) -> "token-of-" + u.username());

        assertThat(user.username()).isEqualTo("phil");
        assertThat(user.principal()).isEqualTo("phil");
        assertThat(user.token()).isEqualTo("token-of-phil");
        assertThat(user.isMember()).isFalse();
    }
}
