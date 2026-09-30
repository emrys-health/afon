package health.emrys.afon.security.keycloak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyCloakProviderTest {

    @Test
    void refusesAdminTokenWithoutBootstrapAdminCredentials() {
        KeyCloakProvider tokensOnly = new KeyCloakProvider("keycloak.example.com");

        assertThatThrownBy(tokensOnly::adminToken)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("realm 'master'")
            .hasMessageContaining("KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME");
    }

    @Test
    void refusesRealmTokenWithBlankBootstrapAdminCredentials() {
        KeyCloakProvider blankAdmin = new KeyCloakProvider("keycloak.example.com", "admin", " ");

        assertThatThrownBy(() -> blankAdmin.getToken("polaris"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("realm 'polaris'");
    }
}
