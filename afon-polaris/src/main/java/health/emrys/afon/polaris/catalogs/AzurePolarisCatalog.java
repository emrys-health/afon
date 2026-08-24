package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class AzurePolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String tenant;
    private final String multiTenantAppName;
    private final String clientId;

    public AzurePolarisCatalog(AfonPropertyProvider properties) {
        String bucketName = properties.getValue(AZURE_BUCKET);
        this.bucketUrl          = bucketUrl(bucketName, properties.getValue(AZURE_STORAGE_ACCOUNT));
        this.tenant             = properties.getValue(AZURE_TENANT_ID);
        this.multiTenantAppName = properties.getValue(AZURE_CLIENT_ID);
        this.clientId           = properties.getValue(AZURE_CLIENT_ID);
    }

    protected String bucketUri() {
        return bucketUrl;
    }

    public StorageConfigInfo storageConfigInfo() {
        return AzureUtils.getStorageConfigInfo(
            tenant,
            multiTenantAppName,
            bucketUri(),
            clientId);
    }

    @Override
    public String catalogName() {
        return AZURE.toString();
    }
}
