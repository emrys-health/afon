package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the setup of a Polaris makes of its admin client in the identity. */
class ConfigureUsersMainTest {

    private final IdentityProvider identity = mock(IdentityProvider.class);
    private final AfonPropertyProvider properties = mock(AfonPropertyProvider.class);
    private final AfonUserProvider users = mock(AfonUserProvider.class);

    @BeforeEach
    void setUp() {
        when(properties.getValue(POLARIS_ID)).thenReturn("root");
        when(properties.getValue(POLARIS_SECRET)).thenReturn("s3cret");
        when(users.getAdmins()).thenReturn(List.of());
    }

    @Test
    void givesTheAdminClientOfADataProviderItsAudienceAndTheAcrItRequires() throws Exception {
        when(properties.hasProperty(OIDC_AUDIENCE)).thenReturn(true);
        when(properties.hasProperty(OIDC_ACR)).thenReturn(true);
        when(properties.getValue(OIDC_AUDIENCE)).thenReturn("provider-lon");
        when(properties.getValue(OIDC_ACR)).thenReturn("mfa");

        new ConfigureUsersMain(users, properties, identity).configureOidcUsers();

        verify(identity).ensureClientAudience("root", "provider-lon");
        verify(identity).ensureClientAcr("root", "mfa");
    }

    @Test
    void leavesTheAdminClientAsItIsWhereNothingRequiresAnAudience() throws Exception {
        new ConfigureUsersMain(users, properties, identity).configureOidcUsers();

        verify(identity, never()).ensureClientAudience(anyString(), anyString());
        verify(identity, never()).ensureClientAcr(anyString(), anyString());
    }

    @Test
    void leavesTheAdminClientAsItIsWhereTheConfigHasTheKeysEmpty() throws Exception {
        when(properties.hasProperty(OIDC_AUDIENCE)).thenReturn(true);
        when(properties.hasProperty(OIDC_ACR)).thenReturn(true);
        when(properties.getValue(OIDC_AUDIENCE)).thenReturn("");
        when(properties.getValue(OIDC_ACR)).thenReturn("");

        new ConfigureUsersMain(users, properties, identity).configureOidcUsers();

        verify(identity, never()).ensureClientAudience(anyString(), anyString());
        verify(identity, never()).ensureClientAcr(anyString(), anyString());
    }
}
