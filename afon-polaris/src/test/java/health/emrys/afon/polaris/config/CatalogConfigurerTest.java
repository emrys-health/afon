package health.emrys.afon.polaris.config;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private void catalogHasRoles(String json) throws Exception {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        when(response.getEntity()).thenReturn(new StringEntity(json));
        when(client.get(eq(ROLES), anyString())).thenReturn(response);
    }

    @Test
    void doesNotDeleteACatalogRoleTheCatalogDoesNotHaveAsPolarisDropsAFederatedCatalogInstead() throws Exception {
        catalogHasRoles("{\"roles\":[{\"name\":\"catalog_admin\"}]}");

        configurer.deleteCatalogRole("azure_external", "emrysrole4");

        verify(client, never()).delete(anyString(), anyString());
    }

    @Test
    void doesNotDeleteACatalogRoleOfACatalogThatDoesNotExist() throws Exception {
        catalogHasRoles("{\"error\":{\"message\":\"TopLevelEntity of type CATALOG does not exist\",\"code\":404}}");

        configurer.deleteCatalogRole("azure_external", "emrysrole4");

        verify(client, never()).delete(anyString(), anyString());
    }

    @Test
    void deletesACatalogRoleTheCatalogHas() throws Exception {
        catalogHasRoles("{\"roles\":[{\"name\":\"catalog_admin\"},{\"name\":\"emrysrole4\"}]}");
        when(client.delete(anyString(), anyString())).thenReturn(mock(ClassicHttpResponse.class));

        configurer.deleteCatalogRole("azure_external", "emrysrole4");

        verify(client).delete(ROLES + "/emrysrole4", "token");
    }
}
