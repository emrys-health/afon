package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonProperty.AZURE_APP_ID;
import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.config.AfonProperty.AZURE_TENANT_ID;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;

public class AzurePolarisCatalog extends PolarisCatalog {

    private final String catalogName;
    private final boolean adminGrants;
    private final String bucketUrl;
    private final String tenant;
    private final String multiTenantAppName;
    private final String clientId;

    /**
     * Builds any "internal"-typed Azure catalog purely from its {@link AfonCatalog} definition;
     * credentials are fixed per cloud and read here directly.
     */
    public AzurePolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        this.catalogName        = catalog.toString();
        this.adminGrants        = catalog.adminGrants();
        this.bucketUrl          = abfssBucketUrl(catalog.bucketName(), properties.getValue(AZURE_STORAGE_ACCOUNT));
        this.tenant             = properties.getValue(AZURE_TENANT_ID);
        this.multiTenantAppName = properties.getValue(AZURE_APP_ID);
        this.clientId           = properties.getValue(AZURE_APP_ID);
    }

    protected String bucketUri() {
        return bucketUrl;
    }

    public StorageConfigInfo storageConfigInfo() {
        return AzureUtils.createAzureStorageConfigInfo(
            tenant,
            multiTenantAppName,
            bucketUri(),
            clientId);
    }

    @Override
    public String catalogName() {
        return catalogName;
    }

    @Override
    public boolean adminGrants() {
        return adminGrants;
    }
}
