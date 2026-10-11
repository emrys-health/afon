package health.emrys.afon.polaris.config;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.rest.HttpResponses.isFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogConfigurerTest {

    private static final String ROLES = "/api/management/v1/catalogs/azure_external/catalog-roles";

    private final PolarisRestClient client = mock(PolarisRestClient.class);
    private CatalogConfigurer configurer;

    @BeforeEach
    void configurer() {
        when(client.getAccessToken()).thenReturn("token");
        configurer = new CatalogConfigurer(client);
    }

    private static final String ROLE = ROLES + "/emrysrole4";

    private void catalogHasRoles(String json) throws Exception {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        when(response.getEntity()).thenReturn(new StringEntity(json));
        when(client.get(eq(ROLES), anyString())).thenReturn(response);
    }

    private void exists(String path, boolean exists) throws Exception {
        when(client.exists(eq(path), anyString())).thenReturn(exists);
    }

    @Test
    void doesNotDeleteACatalogRoleTheCatalogDoesNotHaveAsPolarisDropsAFederatedCatalogInstead() throws Exception {
        catalogHasRoles("{\"roles\":[{\"name\":\"catalog_admin\"}]}");

        configurer.deleteCatalogRole("azure_external", "emrysrole4");

        verify(client, never()).delete(anyString(), anyString());
    }

    @Test
    void deletesACatalogRoleTheCatalogHas() throws Exception {
        catalogHasRoles("{\"roles\":[{\"name\":\"catalog_admin\"},{\"name\":\"emrysrole4\"}]}");
        ClassicHttpResponse r204 = response(204);
        when(client.delete(anyString(), anyString())).thenReturn(r204);

        configurer.deleteCatalogRole("azure_external", "emrysrole4");

        verify(client).delete(ROLE, "token");
    }

    @Test
    void doesNotDeleteWhatIsAbsent() throws Exception {
        exists("/api/management/v1/catalogs/c", false);
        exists("/api/management/v1/principals/p", false);
        exists("/api/management/v1/principal-roles/r", false);

        assertThat(isFailure(configurer.deleteCatalog("c"))).isFalse();
        assertThat(isFailure(configurer.deletePrincipal("p"))).isFalse();
        assertThat(isFailure(configurer.deletePrincipalRole("r"))).isFalse();

        verify(client, never()).delete(anyString(), anyString());
    }

    @Test
    void deletesWhatExists() throws Exception {
        exists("/api/management/v1/catalogs/c", true);
        exists("/api/management/v1/principals/p", true);
        exists("/api/management/v1/principal-roles/r", true);
        ClassicHttpResponse r204 = response(204);
        when(client.delete(anyString(), anyString())).thenReturn(r204);

        configurer.deleteCatalog("c");
        configurer.deletePrincipal("p");
        configurer.deletePrincipalRole("r");

        verify(client).delete("/api/management/v1/catalogs/c", "token");
        verify(client).delete("/api/management/v1/principals/p", "token");
        verify(client).delete("/api/management/v1/principal-roles/r", "token");
    }

    @Test
    void doesNotCreateWhatExistsAndDoesNotCountItAsAFailure() throws Exception {
        catalogHasRoles("{\"roles\":[{\"name\":\"emrysrole4\"}]}");
        exists("/api/management/v1/principals/p", true);
        exists("/api/management/v1/principal-roles/r", true);

        assertThat(isFailure(configurer.createCatalogRole("azure_external", "emrysrole4"))).isFalse();
        assertThat(isFailure(configurer.createPrincipal("p"))).isFalse();
        assertThat(isFailure(configurer.createPrincipalRole("r"))).isFalse();

        verify(client, never()).post(anyString(), anyString(), anyString());
    }

    @Test
    void createsWhatIsAbsent() throws Exception {
        exists("/api/management/v1/principals/p", false);
        ClassicHttpResponse r201 = response(201);
        when(client.post(anyString(), anyString(), anyString())).thenReturn(r201);

        configurer.createPrincipal("p");

        verify(client).post(eq("/api/management/v1/principals"), anyString(), eq("token"));
    }

    @Test
    void doesNotCreateACatalogThatExists() throws Exception {
        PolarisCatalog catalog = mock(PolarisCatalog.class);
        when(catalog.catalogName()).thenReturn("c");
        exists("/api/management/v1/catalogs/c", true);

        assertThat(isFailure(configurer.createCatalogIfAbsent(catalog))).isFalse();

        verify(client, never()).post(anyString(), anyString(), anyString());
    }

    @Test
    void configuringAnExistingCatalogNeitherDeletesNorCreatesIt() throws Exception {
        PolarisCatalog catalog = mock(PolarisCatalog.class);
        when(catalog.catalogName()).thenReturn("c");
        exists("/api/management/v1/catalogs/c", true);

        configurer.configureCatalog(catalog);

        verify(client, never()).delete(anyString(), anyString());
        verify(client, never()).post(anyString(), anyString(), anyString());
    }

    @Test
    void stillReportsARealFailure() throws Exception {
        exists("/api/management/v1/principals/p", false);
        ClassicHttpResponse r403 = response(403);
        when(client.post(anyString(), anyString(), anyString())).thenReturn(r403);

        assertThat(isFailure(configurer.createPrincipal("p"))).isTrue();
    }

    private static ClassicHttpResponse response(int code) {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(code);
        return response;
    }
}
