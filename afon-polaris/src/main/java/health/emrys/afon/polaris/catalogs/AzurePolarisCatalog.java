package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.StorageConfigInfo;

import java.util.Properties;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class AzurePolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String tenant;
    private final String multiTenantAppName;
    private final String clientId;

    public AzurePolarisCatalog(Properties properties) {
        String bucketName = AZURE_BUCKET.getValueFrom(properties);
        this.bucketUrl          = bucketUrl(bucketName, AZURE_STORAGE_ACCOUNT.getValueFrom(properties));
        this.tenant             = AZURE_TENANT_ID.getValueFrom(properties);
        this.multiTenantAppName = AZURE_CLIENT_ID.getValueFrom(properties);
        this.clientId           = AZURE_CLIENT_ID.getValueFrom(properties);
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
