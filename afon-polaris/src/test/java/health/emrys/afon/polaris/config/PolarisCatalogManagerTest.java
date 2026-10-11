package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PolarisCatalogManagerTest {

    private final CatalogConfigurer configurer = mock(CatalogConfigurer.class);
    private PolarisCatalogManager manager;

    @BeforeEach
    void manager() {
        Properties raw = new Properties();
        raw.setProperty("AWS_CATALOG_IDS", "aws,aws_external,aws_glue");
        raw.setProperty("AWS_CATALOG_TYPE_aws", "internal");
        raw.setProperty("AWS_CATALOG_TYPE_aws_external", "external");
        raw.setProperty("AWS_CATALOG_TYPE_aws_glue", "glue");
        raw.setProperty("AWS_CATALOG_NAME_aws_glue", "a_glue_database");
        AfonPropertyProvider properties = mock(AfonPropertyProvider.class);
        when(properties.getProperties()).thenReturn(raw);
        manager = new PolarisCatalogManager(properties, new AfonCatalogs(properties), configurer);
    }

    @Test
    void purgesNamespacesTablesAndRolesBeforeDeletingAnInternalCatalog() throws Exception {
        when(configurer.namespacesOf("aws")).thenReturn(List.of("ns"));
        when(configurer.tableNamesOf("aws", "ns")).thenReturn(List.of("t"));
        when(configurer.catalogRoleNames("aws")).thenReturn(List.of("catalog_admin", "emrysrole4"));

        manager.deleteCatalog("aws");

        InOrder order = inOrder(configurer);
        order.verify(configurer).deleteTable("aws", "ns", "t");
        order.verify(configurer).deleteNamespace("aws", "ns");
        order.verify(configurer).deleteCatalogRole("aws", "emrysrole4");
        order.verify(configurer).deleteCatalog("aws");
        verify(configurer, never()).deleteCatalogRole("aws", "catalog_admin");
    }

    @Test
    void leavesTheRemoteNamespacesAndTablesOfAFederatedCatalogButPurgesItsRoles() throws Exception {
        when(configurer.catalogRoleNames("aws_external")).thenReturn(List.of("catalog_admin", "emrysrole4"));

        manager.deleteCatalog("aws_external");

        verify(configurer, never()).namespacesOf(anyString());
        verify(configurer, never()).deleteNamespace(anyString(), anyString());
        verify(configurer, never()).deleteTable(anyString(), anyString(), anyString());
        InOrder order = inOrder(configurer);
        order.verify(configurer).deleteCatalogRole("aws_external", "emrysrole4");
        order.verify(configurer).deleteCatalog("aws_external");
    }

    @Test
    void deletesByIdUsingThePolarisNameOfTheCatalog() throws Exception {
        manager.deleteCatalog("aws_glue");
        manager.deleteNamespace("aws_glue", "ns");
        manager.deleteTable("aws_glue", "ns", "t");

        verify(configurer).deleteCatalog("a_glue_database");
        verify(configurer).deleteNamespace("a_glue_database", "ns");
        verify(configurer).deleteTable("a_glue_database", "ns", "t");
    }

    @Test
    void refusesAnUnknownCatalogInsteadOfSendingItToPolaris() {
        assertThatThrownBy(() -> manager.deleteCatalog("a_glue_database"))
            .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(configurer);
    }
}
