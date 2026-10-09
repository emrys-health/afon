package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.polaris.config.PolarisCatalogManager;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class ManagePolarisCatalogsMainTest {

    private final PolarisCatalogManager manager = mock(PolarisCatalogManager.class);
    private final ManagePolarisCatalogsMain main =
        new ManagePolarisCatalogsMain(manager, mock(AfonCatalogs.class), "polaris.example");

    @Test
    void deletesTheCatalogWhenNoNamespaceIsNamed() throws Exception {
        main.delete("azure", null, null);

        verify(manager).deleteCatalog("azure");
        verifyNoMoreInteractions(manager);
    }

    @Test
    void deletesJustTheNamespaceWhenNoTableIsNamed() throws Exception {
        main.delete("azure", "namespaceph", null);

        verify(manager).deleteNamespace("azure", "namespaceph");
        verify(manager, never()).deleteCatalog(anyString());
        verifyNoMoreInteractions(manager);
    }

    @Test
    void deletesJustTheTableNotItsCatalogOrNamespace() throws Exception {
        main.delete("azure", "namespaceph", "test_table");

        verify(manager).deleteTable("azure", "namespaceph", "test_table");
        verify(manager, never()).deleteCatalog(anyString());
        verify(manager, never()).deleteNamespace(anyString(), anyString());
        verifyNoMoreInteractions(manager);
    }
}
