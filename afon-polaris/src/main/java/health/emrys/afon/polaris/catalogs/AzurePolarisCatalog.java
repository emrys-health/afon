package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;

public class AzurePolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String tenant;
    private final String multiTenantAppName;
    private final String clientId;

    public AzurePolarisCatalog(AfonPropertyProvider properties) {
        this.bucketUrl          = abfssBucketUrl(properties.getValue(AZURE_BUCKET_NAME), properties.getValue(AZURE_STORAGE_ACCOUNT));
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
        return "azure";
    }
}
