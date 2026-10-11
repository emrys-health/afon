package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserAcl;
import health.emrys.afon.config.AfonUserProvider;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.polaris.config.PolarisUserManager.DEFAULT_CATALOG_PRIVILEGES;
import static health.emrys.afon.polaris.config.PolarisUserManager.DEFAULT_CATALOG_ROLE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PolarisUserManagerTest {

    private static final String CATALOG_ROLE = DEFAULT_CATALOG_ROLE_NAME;
    private static final String SHARED_ROLE  = CATALOG_ROLE + "Principal";

    private final CatalogConfigurer configurer = mock(CatalogConfigurer.class);
    private final PolarisUserManager manager =
        new PolarisUserManager(configurer, "root", CATALOG_ROLE, DEFAULT_CATALOG_PRIVILEGES);

    @BeforeEach
    void polaris() throws Exception {
        ClassicHttpResponse created = mock(ClassicHttpResponse.class);
        when(created.getCode()).thenReturn(201);
        when(configurer.createCatalogRole(anyString(), anyString())).thenReturn(created);
    }

    @Test
    void grantsCatalogOnlyToThePrincipalRolesOfAuthorisedUsers() throws Exception {
        manager.configureUsers("aws_forbidden", users(Map.of("will", "aws", "phil", "all")));

        verify(configurer).associatePrincipalRole("aws_forbidden", "philPrincipal", CATALOG_ROLE);
        verify(configurer).associatePrincipalRole("phil", "philPrincipal");
        verify(configurer, never()).associatePrincipalRole(eq("aws_forbidden"), eq("willPrincipal"), anyString());
        verify(configurer, never()).associatePrincipalRole(eq("will"), anyString());
        verify(configurer, never()).associatePrincipalRole(anyString(), eq(SHARED_ROLE), anyString());
        verify(configurer, never()).associatePrincipalRole(anyString(), eq(SHARED_ROLE));
    }

    @Test
    void recreatesTheCatalogRoleButKeepsUserPrincipalRoles() throws Exception {
        AfonUserProvider users = users(Map.of("will", "aws:gcp"));
        manager.configureUsers("aws", users);
        manager.configureUsers("gcp", users);

        verify(configurer).deleteCatalogRole("aws", CATALOG_ROLE);
        verify(configurer).deleteCatalogRole("gcp", CATALOG_ROLE);
        verify(configurer).associatePrincipalRole("aws", "willPrincipal", CATALOG_ROLE);
        verify(configurer).associatePrincipalRole("gcp", "willPrincipal", CATALOG_ROLE);
        verify(configurer, times(2)).createPrincipalRole("willPrincipal");
        verify(configurer, never()).deletePrincipalRole("willPrincipal");
    }

    @Test
    void removesTheSharedPrincipalRoleOnce() throws Exception {
        AfonUserProvider users = users(Map.of("will", "all"));
        manager.configureUsers("aws", users);
        manager.configureUsers("gcp", users);

        verify(configurer, times(1)).deletePrincipalRole(SHARED_ROLE);
        verify(configurer, never()).createPrincipalRole(SHARED_ROLE);
    }

    @Test
    void createsAUserPrincipalOnlyIfAbsentSoItsCredentialsAreKept() throws Exception {
        manager.configureUsers("aws", users(Map.of("will", "aws:gcp")));
        manager.configureUsers("gcp", users(Map.of("will", "aws:gcp")));

        verify(configurer, times(2)).createPrincipal("will");
        verify(configurer, never()).deletePrincipal(anyString());
    }

    @Test
    void namesPrincipalRolesAfterUsers() {
        assertThat(PolarisUserManager.userPrincipalRoleName("will")).isEqualTo("willPrincipal");
        assertThat(PolarisUserManager.sharedPrincipalRoleName(CATALOG_ROLE)).isEqualTo(SHARED_ROLE);
    }

    private static AfonUserProvider users(Map<String, String> acls) {
        return new AfonUserProvider() {
            @Override
            public List<AfonUser> getUsers() {
                return acls.keySet().stream().sorted().map(PolarisUserManagerTest::user).toList();
            }

            @Override
            public List<String> getUserNames() {
                return acls.keySet().stream().sorted().toList();
            }

            @Override
            public AfonUser getUser(String username) {
                return user(username);
            }

            @Override
            public List<AfonUserAcl> getUserAcls() {
                return acls.entrySet().stream().map(e -> new AfonUserAcl(e.getKey(), e.getValue())).toList();
            }

            @Override
            public boolean isAuthorised(String username, String catalogName) {
                return new AfonUserAcl(username, acls.get(username)).isAuthorised(catalogName);
            }
        };
    }

    private static AfonUser user(String username) {
        return new AfonUser(username, "password", username + "@test.com", "slug", "first", "second");
    }
}
