package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void registersTheBrowserClientOfTheEnvWhereThereIsNoProject() throws Exception {
        when(properties.getValue(OAUTH2_CLIENT_ID)).thenReturn("env-client");
        when(properties.getValue(OAUTH2_CLIENT_SECRET)).thenReturn("env-secret");

        new ConfigureUsersMain(users, properties, identity).configureJupyterClient("jupyter.example.org");

        verify(identity).registerBrowserClient(eq("env-client"), eq("env-secret"), anyList());
        verify(identity).configureMfa();
    }

    @Test
    void leavesTheClientOfAProjectToTheIdentityThatMakesIt() throws Exception {
        when(properties.hasProperty(OIDC_PROJECT_CLAIM)).thenReturn(true);
        when(properties.getValue(OIDC_PROJECT_CLAIM)).thenReturn("project");
        when(properties.getValue(OAUTH2_CLIENT_ID)).thenReturn("xyz-yyyy-nnnn-tre");

        new ConfigureUsersMain(users, properties, identity).configureJupyterClient("jupyter.example.org");

        verify(identity, never()).registerBrowserClient(anyString(), anyString(), anyList());
        verify(identity).configureMfa();
    }

    @Test
    void registersTheBrowserClientOfTheEnvWhereTheConfigHasTheProjectClaimEmpty() throws Exception {
        when(properties.hasProperty(OIDC_PROJECT_CLAIM)).thenReturn(true);
        when(properties.getValue(OIDC_PROJECT_CLAIM)).thenReturn("");
        when(properties.getValue(OAUTH2_CLIENT_ID)).thenReturn("env-client");
        when(properties.getValue(OAUTH2_CLIENT_SECRET)).thenReturn("env-secret");

        new ConfigureUsersMain(users, properties, identity).configureJupyterClient("jupyter.example.org");

        verify(identity).registerBrowserClient(eq("env-client"), eq("env-secret"), anyList());
    }

    private static final AfonUser MAURO = new AfonUser("mauro", "pw-mauro", "mauro.talevi@emrys.health", "mauro-talevi", "Mauro", "Talevi");
    private static final AfonUser WILL = new AfonUser("will", "pw-will", "will@emrys.health", "will", "Will", "X");

    @Test
    void makesTheAdminUsersOfUsersCsvAndTheClientOfTheUserOutsideAProject() throws Exception {
        when(users.getAdmins()).thenReturn(List.of(MAURO, WILL));
        when(properties.getValue(USER_ID)).thenReturn("will");

        new ConfigureUsersMain(users, properties, identity).configureOidcUsers();

        verify(identity).createUser(any(), eq("polaris"), eq(MAURO));
        verify(identity).createUser(any(), eq("polaris"), eq(WILL));
        verify(identity).createConfidentialClient(any(), eq("polaris"), eq("will"), eq("pw-will"));
    }

    @Test
    void leavesTheUsersOfTheSharedIdentityAloneForAProjectsTre() throws Exception {
        when(users.getAdmins()).thenReturn(List.of(MAURO, WILL));
        when(properties.getValue(USER_ID)).thenReturn("will");
        when(properties.hasProperty(OIDC_PROJECT_CLAIM)).thenReturn(true);
        when(properties.getValue(OIDC_PROJECT_CLAIM)).thenReturn("project");

        new ConfigureUsersMain(users, properties, identity).configureOidcUsers();

        // no user is made or has a password set, and no client is made for the user, but the realm and the admin client of Polaris still are
        verify(identity, never()).createUser(any(), anyString(), any());
        verify(identity, never()).createConfidentialClient(any(), anyString(), eq("will"), anyString());
        verify(identity).createRealm(any(), eq("polaris"));
        verify(identity).createConfidentialClient(any(), eq("polaris"), eq("root"), eq("s3cret"));
    }

    @Test
    void makesTheAdminUsersWhereTheConfigHasTheProjectClaimEmpty() throws Exception {
        when(users.getAdmins()).thenReturn(List.of(MAURO));
        when(properties.hasProperty(OIDC_PROJECT_CLAIM)).thenReturn(true);
        when(properties.getValue(OIDC_PROJECT_CLAIM)).thenReturn("");

        new ConfigureUsersMain(users, properties, identity).configureOidcUsers();

        verify(identity).createUser(any(), eq("polaris"), eq(MAURO));
    }
}
