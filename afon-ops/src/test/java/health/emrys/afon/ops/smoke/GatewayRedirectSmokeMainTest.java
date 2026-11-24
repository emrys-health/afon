package health.emrys.afon.ops.smoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRedirectSmokeMainTest {

    @Test
    void acceptsRedirectToAuthHost() {
        assertThat(GatewayRedirectSmokeMain.validateAuthRedirect(
            "jupyter.example.org", 302, "https://auth.jupyter.example.org/.pomerium/sign_in?pomerium_redirect_uri=https%3A%2F%2Fjupyter.example.org%2F"))
            .isEmpty();
    }

    @Test
    void rejectsNonRedirectOrWrongHostFromJupyter() {
        assertThat(GatewayRedirectSmokeMain.validateAuthRedirect("jupyter.example.org", 200, null)).hasSize(1);
        assertThat(GatewayRedirectSmokeMain.validateAuthRedirect("jupyter.example.org", 302, "https://other.example.org/")).hasSize(1);
    }

    @Test
    void acceptsRedirectFromAuthHostToKeycloakWithAuthCallback() {
        String location = "https://id.example.org/realms/polaris/protocol/openid-connect/auth"
            + "?client_id=tre-browser&redirect_uri=https%3A%2F%2Fauth.jupyter.example.org%2Foauth2%2Fcallback&response_type=code";
        assertThat(GatewayRedirectSmokeMain.validateIdpRedirect(
            "jupyter.example.org", "https://id.example.org/realms/polaris", 302, location))
            .isEmpty();
    }

    @Test
    void rejectsRedirectWithWrongCallbackOrWrongIssuer() {
        String wrongCallback = "https://id.example.org/realms/polaris/protocol/openid-connect/auth"
            + "?redirect_uri=https%3A%2F%2Fjupyter.example.org%2Foauth2%2Fcallback";
        assertThat(GatewayRedirectSmokeMain.validateIdpRedirect(
            "jupyter.example.org", "https://id.example.org/realms/polaris", 302, wrongCallback))
            .hasSize(1);

        String wrongIssuer = "https://evil.example.org/auth?redirect_uri=https%3A%2F%2Fauth.jupyter.example.org%2Foauth2%2Fcallback";
        assertThat(GatewayRedirectSmokeMain.validateIdpRedirect(
            "jupyter.example.org", "https://id.example.org/realms/polaris", 302, wrongIssuer))
            .hasSize(1);
    }
}
